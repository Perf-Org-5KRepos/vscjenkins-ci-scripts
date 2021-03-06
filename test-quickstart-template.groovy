node('quickstart-template') {
    def scenario_name = "qstest" + UUID.randomUUID().toString().replaceAll("-", "")

    try {
        properties([
            pipelineTriggers([cron('@daily')]),
            buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '14', numToKeepStr: '')),
            parameters([
             string(defaultValue: 'Azure', description: '', name: 'template_fork'),
             string(defaultValue: 'master', description: '', name: 'template_branch')
             ])
        ])

        def utils_location = "https://raw.githubusercontent.com/Azure/jenkins/master/solution_template/"
        // The azure-jenkins template uses an old image that doesn't support the same CLI command that we run
        def run_basic_jenkins_test = env.JOB_BASE_NAME != "azure-jenkins" && env.JOB_BASE_NAME.contains("jenkins")
        def run_jenkins_acr_test = env.JOB_BASE_NAME.contains("jenkins-acr")

        def ssh_command = ""
        def jenkinsAdminPassword = "";
        def socket = scenario_name + "/ssh-socket"

        stage('Deploy Quickstart Template') {
            checkout scm

            def script_path = 'scripts/deploy-quickstart-template.sh'
            sh 'chmod +x ' + script_path
            withCredentials([usernamePassword(credentialsId: 'AzDevOpsTestingSP', passwordVariable: 'app_key', usernameVariable: 'app_id')]) {
                ssh_command = sh(returnStdout: true, script: script_path + ' -tl ' + 'https://raw.githubusercontent.com/' + params.template_fork + '/azure-quickstart-templates/' + params.template_branch + '/' +' -tn ' + env.JOB_BASE_NAME + ' -sn ' + scenario_name + ' -ai ' + env.app_id + ' -ak ' + env.app_key).trim()
            }
        }

        sh ssh_command + ' -S ' + socket + ' -fNTM -o "StrictHostKeyChecking=no"'

        try {
          if (run_basic_jenkins_test || run_jenkins_acr_test) {
              stage('Jenkins Test') {
                  def expectedJobs = [];
                  if (run_jenkins_acr_test)
                    expectedJobs.push("basic-docker-build")
                runJenkinsTests(sshCommand: ssh_command, utilsLocation: utils_location, expectedJobNames: expectedJobs)
              }
          }
        } finally {
          // Always close the socket so that the port is not in use on the agent
          sh ssh_command + ' -S ' + socket + ' -O exit'
        }

        stage('Clean Up') {
          // Only clean up the resource group if all previous stages passed (just in case we want to debug a failure)
          // The clean-deployments job will delete it after 2 days
          sh 'az lock delete -g ' + scenario_name + ' -n del-lock'
          sh 'az group delete -n ' + scenario_name + ' --yes'
        }
    } catch (e) {
        sh 'az lock delete -g ' + scenario_name + ' -n del-lock || true'
        def public_build_url = "$BUILD_URL".replaceAll("$JENKINS_URL" , "$PUBLIC_URL")
        emailext (
            attachLog: true,
            subject: "Jenkins Job '$JOB_NAME' #$BUILD_NUMBER Failed",
            body: public_build_url,
            to: "$TEAM_MAIL_ADDRESS"
        )
        throw e
    } finally {
      sh 'az logout'
      sh 'rm -f ~/.kube/config'
      sh 'rm -rf ' + scenario_name
    }
}