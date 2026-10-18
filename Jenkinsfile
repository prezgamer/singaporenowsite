pipeline {
    // No Docker and no Jenkins tool setup needed: Maven is downloaded once into
    // JENKINS_HOME and reused. Jenkins itself only needs Java 17 or newer and curl.
    agent any

    options {
        timeout(time: 20, unit: 'MINUTES')
    }

    environment {
        MAVEN_VERSION = '3.9.9'
        MAVEN_HOME    = "${JENKINS_HOME}/apache-maven-3.9.9"
    }

    stages {
        stage('Set up Maven') {
            steps {
                sh '''
                    java -version
                    if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
                        echo "Downloading Maven $MAVEN_VERSION ..."
                        curl -fsSL -o /tmp/maven.tgz \
                          "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz"
                        tar -xzf /tmp/maven.tgz -C "$JENKINS_HOME"
                        rm -f /tmp/maven.tgz
                    fi
                    "$MAVEN_HOME/bin/mvn" -version
                '''
            }
        }

        stage('Test') {
            steps {
                sh '"$MAVEN_HOME/bin/mvn" -B test'
            }
            post {
                always {
                    junit 'target/surefire-reports/*.xml'
                }
            }
        }

        stage('Package') {
            steps {
                sh '"$MAVEN_HOME/bin/mvn" -B package -DskipTests'
            }
            post {
                success {
                    archiveArtifacts artifacts: 'target/*.war', fingerprint: true
                }
            }
        }
    }
}