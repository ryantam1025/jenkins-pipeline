pipeline {
    agent any  // Or use a Docker agent with .NET SDK: agent { docker { image 'mcr.microsoft.com/dotnet/sdk:8.0' } }

    tools {
        dotnet 'DotNet8'  // Configure in Jenkins Global Tools (e.g., .NET 8 SDK)
    }

    environment {
        NUGET_PACKAGES = '${WORKSPACE}/.nuget/packages'  // Cache NuGet packages
        DOCKER_HUB_CREDENTIALS = credentials('docker-hub-id')  // Credentials Plugin
    }

    triggers {
        githubPush()  // Or pollSCM('H/5 * * * *') – assumes GitHub plugin webhook
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 45, unit: 'MINUTES')
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                echo 'Source code checked out from Git!'
            }
        }

        stage('Restore') {
            steps {
                sh 'dotnet restore MyWebApp.sln --verbosity minimal'
            }
        }

        stage('Build') {
            steps {
                sh 'dotnet build MyWebApp.sln --configuration Release --no-restore'
                echo 'Application built successfully!'
            }
        }

        stage('Test') {
            parallel {
                stage('Unit Tests') {
                    steps {
                        sh 'dotnet test MyWebApp.Tests/MyWebApp.Tests.csproj --no-build --configuration Release --logger "trx;LogFileName=unit-tests.trx"'
                    }
                    post {
                        always {
                            mstest testResultsFile: "**/*.trx", keepLongStdio: true  // Or nunit for NUnit results
                        }
                    }
                }
                stage('Integration Tests') {
                    steps {
                        sh 'dotnet test IntegrationTests/IntegrationTests.csproj --no-build'
                    }
                }
            }
        }

        stage('Code Quality Scan') {
            steps {
                withSonarQubeEnv('SonarQube Server') {  // Global config in Jenkins
                    sh 'dotnet-sonarscanner begin /k:"mywebapp" /d:sonar.host.url="${SONAR_HOST_URL}"'
                    sh 'dotnet build MyWebApp.sln'
                    sh 'dotnet-sonarscanner end /d:sonar.login="${SONAR_AUTH_TOKEN}"'
                }
            }
        }

        stage('Publish Artifact') {
            when { branch 'main' }
            steps {
                sh 'dotnet publish MyWebApp/MyWebApp.csproj --configuration Release --output ./publish'
                archiveArtifacts artifacts: 'publish/**', fingerprint: true
            }
        }

        stage('Build & Push Docker Image') {
            when { branch 'main' }
            steps {
                script {
                    def image = docker.build("yourusername/my-aspnet-app:${env.BUILD_ID}")
                    docker.withRegistry('https://registry.hub.docker.com', 'docker-hub-id') {
                        image.push()
                        image.push('latest')
                    }
                }
            }
        }

        stage('Deploy to Staging') {
            when { branch 'main' }
            input { message 'Deploy to staging environment?' }
            steps {
                sh './deploy-staging.sh'  // Or use Kubernetes/Helm/AWS scripts
                echo 'Deployed to staging!'
            }
        }
    }

    post {
        always {
            slackSend channel: '#dotnet-builds', message: "*${currentBuild.result}*: Job ${env.JOB_NAME} #${env.BUILD_NUMBER}\n${env.BUILD_URL}"
        }
        success {
            echo 'Pipeline succeeded – ready for next steps!'
        }
        failure {
            echo 'Pipeline failed – check logs!'
        }
    }
}