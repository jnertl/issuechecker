pipeline {
    agent any

    triggers {
        GenericTrigger(
            genericVariables: [
                [key: 'issue', value: '$.issue.number'],
                [key: 'title', value: '$.issue.title'],
                [key: 'body', value: '$.issue.body'],
                [key: 'action', value: '$.action'],
                [key: 'issue_url', value: '$.issue.url'],
                [key: 'repository_full_name', value: '$.repository.full_name'] // do we need this
            ],
            token: 'omovmlauXnIDP',
            printContributedVariables: true,
            printPostContent: true,
        )
    }

    environment {
        git_checkout_root = '/var/jenkins_home/workspace/issue_check_git_checkout'
        GOOGLE_API_KEY = credentials('GOOGLE_API_KEY_JANI')
        GITHUB_TOKEN = credentials('GITHUB_TOKEN_ISSUES_JANI')
    }
    stages {
        stage('Checkout') {
            steps {
                sh '''
                    echo "Git checkout root:${git_checkout_root}"
                    rm -fr "${git_checkout_root}" || true
                    mkdir -p "${git_checkout_root}"
                    cd "${git_checkout_root}"

                    echo "mwclientwithgui"
                    git clone --single-branch --branch main https://github.com/jnertl/mwclientwithgui.git
                    git --no-pager -C mwclientwithgui/ show --summary

                    echo "testing"
                    git clone --single-branch --branch main https://github.com/jnertl/testing.git
                    git --no-pager -C testing/ show --summary

                    echo "agenttools"
                    git clone --single-branch --branch main https://github.com/jnertl/agenttools.git
                    git --no-pager -C agenttools/ show --summary

                    echo "middlewaresw"
                    git clone --single-branch --branch main https://github.com/jnertl/middlewaresw.git
                    cd middlewaresw
                    git submodule update --init --recursive
                    git --no-pager show --summary
                '''
            }
        }

        stage('Cleanup workspace') {
            steps {
                sh '''
                    rm -fr "${WORKSPACE}/agent_log.txt" || true
                    rm -fr "${WORKSPACE}/agent_response.md" || true
                '''
            }
        }

        stage('Analyze issue') {
            steps {
                sh '''
                    echo "issue number $issue"
                    echo "title $title"
                    echo "body $body"
                    echo "action $action"
                    echo "issue url $issue_url"
                    echo "repository full name $repository_full_name"

                    if [ "$action" != "edited" ] && [ "$action" != "opened" ]; then
                        echo "Action required: edited or opened, currently \"$action\", stopping here"
                        exit 0
                    fi

                    echo 'Analysing GitHub issue...'

                    export SOURCE_ROOT_DIR="$git_checkout_root"

                    # Set up middleware context for analysis
                    export MIDDLEWARE_SOURCE_CODE="${SOURCE_ROOT_DIR}/middlewaresw"

                    # Set up gui client context for analysis
                    export GUI_CLIENT_SOURCE_CODE="${SOURCE_ROOT_DIR}/mwclientwithgui"

                    # Set up integration test case context for analysis
                    export INTEGRATION_TEST_SOURCE_CODE="${SOURCE_ROOT_DIR}/testing/tests/integration/"

                    # Copy integration test requirements for analysis
                    export TEST_REQUIREMENTS_FILE="${SOURCE_ROOT_DIR}/testing/tests/integration/integration_testing_requirements.md"

                    export ISSUE_TITLE="$title"
                    export ISSUE_BODY="$body"

                    cd "${SOURCE_ROOT_DIR}/agenttools"
                    ~/.local/bin/uv venv agent_venv
                    . agent_venv/bin/activate
                    ~/.local/bin/uv pip install -r requirements.txt --link-mode=copy

                    export OLLAMA_BASE_URL="http://localhost:11434"
                    export SYSTEM_PROMPT_FILE="${WORKSPACE}/system_prompts/github_issue_checker.txt"

                    PROVIDER="ollama"
                    MODEL="granite4:micro-h"
                    #PROVIDER="gemini"
                    #MODEL="gemini-2.5-flash"

                    bash "$SOURCE_ROOT_DIR/testing/scripts/ongoing_printer.sh" \
                    python -m agenttools.agent --provider "$PROVIDER" --silent --model "$MODEL" --query "Analyse"

                    cp agent_log.txt "${WORKSPACE}/agent_log.txt" || true
                    python "$SOURCE_ROOT_DIR/testing/scripts/clean_markdown_utf8.py" \
                        "agent_response.md" \
                        "$WORKSPACE/agent_response.md"

                    AGENT_RESPONSE_CONTENT=$(cat "$WORKSPACE/agent_response.md" || echo "No response generated.")
                    python ./scripts/github_comment.py --repo $repository_full_name --issue $issue --body "$AGENT_RESPONSE_CONTENT" --token $GITHUB_TOKEN

                    echo 'Analysing GitHub issue completed.'
                '''
            }
        }
    }

    post {
        always {
            archiveArtifacts(
                artifacts: 'agent_log.txt',
                fingerprint: true,
                allowEmptyArchive: true
            )
            archiveArtifacts(
                artifacts: 'agent_response.md',
                fingerprint: true,
                allowEmptyArchive: true
            )
        }
        success {
            echo 'Build succeeded!'
        }
        failure {
            echo 'Build failed!'
        }
    }
}
