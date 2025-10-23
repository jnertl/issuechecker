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
                    rm -fr "${WORKSPACE}/issue_ticket_analysis.md" || true
                    rm -fr "${WORKSPACE}/integration_testing_analysis.md" || true
                    rm -fr "${WORKSPACE}/middlewaresw_developer_analysis.md" || true
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

                    #PROVIDER="ollama"
                    #MODEL="granite4:micro-h"
                    PROVIDER="gemini"
                    MODEL="gemini-2.5-flash"

                    echo "**********************************************************" >> agent_log.txt
                    echo "Proceeding to issue ticket analysis..."                     >> agent_log.txt
                    echo "**********************************************************" >> agent_log.txt

                    rm -fr "agent_response.md" || true
                    ISSUE_TICKET_ANALYSIS="issue_ticket_analysis.md"
                    export SYSTEM_PROMPT_FILE="${WORKSPACE}/system_prompts/github_issue_checker.txt"
                    bash "$SOURCE_ROOT_DIR/testing/scripts/ongoing_printer.sh" \
                    python -m agenttools.agent --provider "$PROVIDER" --silent --model "$MODEL" --query "Analyse"

                    python "$SOURCE_ROOT_DIR/testing/scripts/clean_markdown_utf8.py" \
                        "agent_response.md" \
                        "$WORKSPACE/$ISSUE_TICKET_ANALYSIS"

                    AGENT_RESPONSE_CONTENT=$(cat "$WORKSPACE/$ISSUE_TICKET_ANALYSIS" || echo "No response generated.")
                    python ./scripts/github_comment.py --repo $repository_full_name --issue $issue --body "$AGENT_RESPONSE_CONTENT" --token $GITHUB_TOKEN

                    # Check if AGENT_RESPONSE_CONTENT contains the exact marker
                    if printf '%s\n' "$AGENT_RESPONSE_CONTENT" | grep -F -q '[TICKET IS CLEAR]'; then
                        echo "Analysis: [TICKET IS CLEAR]"
                    else
                        echo 'Analysing GitHub issue completed. [TICKET IS NOT CLEAR]'
                        cp agent_log.txt "${WORKSPACE}/agent_log.txt" || true
                        exit 0
                    fi

                    echo "\n\n\n" >> agent_log.txt
                    echo "**********************************************************" >> agent_log.txt
                    echo "Proceeding to middlewaresw analysis..."                     >> agent_log.txt
                    echo "**********************************************************" >> agent_log.txt

                    rm -fr "agent_response.md" || true
                    export ISSUE_TICKET_FOR_MIDDLEWARESW="$WORKSPACE/$ISSUE_TICKET_ANALYSIS"
                    export SYSTEM_PROMPT_FILE="${WORKSPACE}/system_prompts/middlewaresw_developer.txt"
                    bash "$SOURCE_ROOT_DIR/testing/scripts/ongoing_printer.sh" \
                    python -m agenttools.agent --provider "$PROVIDER" --silent --model "$MODEL" --query "Analyse"

                    python "$SOURCE_ROOT_DIR/testing/scripts/clean_markdown_utf8.py" \
                        "agent_response.md" \
                        "$WORKSPACE/middlewaresw_developer_analysis.md"

                    GIT_DIFF=$(git -C ${SOURCE_ROOT_DIR}/middlewaresw diff)

                    AGENT_RESPONSE_CONTENT=$(cat "$WORKSPACE/middlewaresw_developer_analysis.md" || echo "No response generated.")
                    AGENT_RESPONSE_CONTENT=$(printf '%s\n\n```diff\n%s\n```\n' "$AGENT_RESPONSE_CONTENT" "$GIT_DIFF")
                    python ./scripts/github_comment.py --repo $repository_full_name --issue $issue --body "$AGENT_RESPONSE_CONTENT" --token $GITHUB_TOKEN


                    echo "\n\n\n" >> agent_log.txt
                    echo "**********************************************************" >> agent_log.txt
                    echo "Proceeding to integration testing analysis..."              >> agent_log.txt
                    echo "**********************************************************" >> agent_log.txt

                    rm -fr "agent_response.md" || true
                    export ISSUE_TICKET_FOR_INTEGRATION_TESTING="$WORKSPACE/$ISSUE_TICKET_ANALYSIS"
                    export SYSTEM_PROMPT_FILE="${WORKSPACE}/system_prompts/integration_testing.txt"
                    bash "$SOURCE_ROOT_DIR/testing/scripts/ongoing_printer.sh" \
                    python -m agenttools.agent --provider "$PROVIDER" --silent --model "$MODEL" --query "Analyse"

                    python "$SOURCE_ROOT_DIR/testing/scripts/clean_markdown_utf8.py" \
                        "agent_response.md" \
                        "$WORKSPACE/integration_testing_analysis.md"

                    GIT_DIFF=$(git -C ${SOURCE_ROOT_DIR}/testing diff)
                    
                    AGENT_RESPONSE_CONTENT=$(cat "$WORKSPACE/integration_testing_analysis.md" || echo "No response generated.")
                    if [ -n "$GIT_DIFF" ]; then
                        AGENT_RESPONSE_CONTENT=$(printf '%s\n\n```diff\n%s\n```\n' "$AGENT_RESPONSE_CONTENT" "$GIT_DIFF")
                    else
                        echo "No git diff for testing" >> agent_log.txt
                    fi
                    python ./scripts/github_comment.py --repo $repository_full_name --issue $issue --body "$AGENT_RESPONSE_CONTENT" --token $GITHUB_TOKEN

                    cp agent_log.txt "${WORKSPACE}/agent_log.txt" || true
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
                artifacts: 'issue_ticket_analysis.md',
                fingerprint: true,
                allowEmptyArchive: true
            )
            archiveArtifacts(
                artifacts: 'middlewaresw_developer_analysis.md',
                fingerprint: true,
                allowEmptyArchive: true
            )
            archiveArtifacts(
                artifacts: 'integration_testing_analysis.md',
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
