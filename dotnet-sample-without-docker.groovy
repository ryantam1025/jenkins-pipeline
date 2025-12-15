pipeline {
    agent any  // Preferably a Windows agent for IIS tools, or Linux with dotnet CLI

    tools {
        dotnet 'DotNet8'  // Configured in Jenkins > Global Tool Configuration (e.g., .NET 8 SDK)
    }

    environment {
        SOLUTION_FILE = 'MyWebApp.sln'
        PROJECT_PATH = 'MyWebApp/MyWebApp.csproj'
        PUBLISH_OUTPUT = 'publish'
    }

    triggers {
        githubPush()  // GitHub webhook trigger
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '15'))
        timeout(time: 60, unit: 'MINUTES')
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                echo 'Source checked out!'
            }
        }

        stage('Restore Packages') {
            steps {
                sh 'dotnet restore ${SOLUTION_FILE}'
            }
        }

        stage('Build') {
            steps {
                sh 'dotnet build ${SOLUTION_FILE} --configuration Release --no-restore'
                echo 'Build completed!'
            }
        }

        stage('Test') {
            parallel {
                stage('Unit Tests') {
                    steps {
                        sh 'dotnet test Tests/UnitTests.csproj --no-build --configuration Release --logger "trx"'
                    }
                    post {
                        always {
                            mstest testResultsFile: '**/*.trx'  // Publish test results
                        }
                    }
                }
                stage('Integration Tests') {
                    steps {
                        sh 'dotnet test Tests/IntegrationTests.csproj --no-build'
                    }
                }
            }
        }

        stage('Code Quality Scan') {
            steps {
                withSonarQubeEnv('SonarQube Server') {
                    sh 'dotnet-sonarscanner begin /k:"mywebapp" /d:sonar.host.url="${SONAR_HOST_URL}"'
                    sh 'dotnet build ${SOLUTION_FILE}'
                    sh 'dotnet-sonarscanner end'
                }
            }
        }

        stage('Publish') {
            when { branch 'main' }
            steps {
                sh 'dotnet publish ${PROJECT_PATH} --configuration Release --output ${PUBLISH_OUTPUT} --no-build'
                archiveArtifacts artifacts: '${PUBLISH_OUTPUT}/**', fingerprint: true
                echo 'Application published as self-contained package!'
            }
        }

        stage('Deploy to Staging') {
            when { branch 'main' }
            input { message 'Approve deployment to staging IIS server?' }
            steps {
                // Example: Copy files via SSH to a Windows server (Publish Over SSH Plugin)
                sshPublisher(
                    publishers: [
                        sshPublisherDesc(
                            configName: 'Staging-IIS-Server',  // Configured in Jenkins credentials
                            transfers: [
                                sshTransfer(
                                    sourceFiles: '${PUBLISH_OUTPUT}/**',
                                    remoteDirectory: '/MyWebApp',
                                    execCommand: 'powershell -File C:\\scripts\\Restart-IIS-App.ps1'
                                )
                            ]
                        )
                    ]
                )
                // Alternative: Use MSDeploy for IIS (if on Windows agent)
                // bat 'msdeploy -verb:sync -source:package="publish.zip" -dest:auto,computerName="staging-server"'
                echo 'Deployed to staging!'
            }
        }
    }

    post {
        always {
            slackSend channel: '#dotnet-builds', message: "${currentBuild.result}: ${env.JOB_NAME} #${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
        }
        success {
            echo 'Pipeline succeeded!'
        }
        failure {
            echo 'Pipeline failed – investigate!'
        }
    }
}