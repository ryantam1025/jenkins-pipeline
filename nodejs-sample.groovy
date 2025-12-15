pipeline {
    agent any  // Run on any available agent

    tools {
        nodejs 'NodeJS'  // Uses globally configured NodeJS installation (from NodeJS Plugin)
    }

    environment {
        DOCKER_HUB_CREDENTIALS = credentials('docker-hub-credentials')  // Secure credentials (Credentials Binding Plugin)
    }

    triggers {
        pollSCM('H/5 * * * *')  // Poll Git every 5 minutes (or use GitHub webhook for instant triggers)
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))  // Keep only last 10 builds
        timeout(time: 30, unit: 'MINUTES')  // Fail if pipeline takes >30 min
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm  // Pull code from Git
                echo 'Code checked out!'
            }
        }

        stage('Build') {
            steps {
                sh 'npm install'  // Install dependencies
                sh 'npm run build'  // Build the app (if applicable)
                echo 'App built successfully!'
            }
        }

        stage('Test') {
            parallel {  // Run tests in parallel for speed
                stage('Unit Tests') {
                    steps {
                        sh 'npm test'  // Run tests (assume Jest or Mocha)
                    }
                    post {
                        always {
                            junit 'test-results/**/*.xml'  // Publish results (JUnit Plugin)
                        }
                    }
                }
                stage('Lint') {
                    steps {
                        sh 'npm run lint'
                    }
                }
            }
        }

        stage('Code Quality Scan') {
            steps {
                withSonarQubeEnv('SonarQube Server') {  // Configured in Jenkins global settings
                    sh 'sonar-scanner'  // SonarQube Scanner Plugin
                }
            }
        }

        stage('Build Docker Image') {
            when { branch 'main' }  // Only on main branch
            steps {
                script {
                    dockerImage = docker.build("yourusername/node-app:${env.BUILD_ID}")
                }
            }
        }

        stage('Push to Docker Hub') {
            when { branch 'main' }
            steps {
                script {
                    docker.withRegistry('https://registry.hub.docker.com', 'docker-hub-credentials') {
                        dockerImage.push()
                        dockerImage.push('latest')
                    }
                }
            }
        }

        stage('Deploy to Staging') {
            when { branch 'main' }
            input { message 'Approve deployment to staging?' }  // Manual approval gate
            steps {
                sh 'docker-compose -f docker-compose.staging.yml up -d'  // Simple deploy
                echo 'Deployed to staging!'
            }
        }
    }

    post {
        always {
            slackSend channel: '#builds', message: "Build ${currentBuild.result} - ${env.JOB_NAME} #${env.BUILD_NUMBER}"  // Slack Plugin
        }
        success {
            echo 'Pipeline succeeded!'
        }
        failure {
            echo 'Pipeline failed!'
        }
    }
}