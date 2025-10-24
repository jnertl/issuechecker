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
                    rm -fr "${WORKSPACE}/middlewaresw_analysis.md" || true
                    rm -fr "${WORKSPACE}/mwclientwithgui_analysis.md" || true
                    rm -fr "${WORKSPACE}/integration_testing_analysis.md" || true
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

                    export AGENT_LOG="${WORKSPACE}/agent_log.txt"
                    export AGENT_TOOLS_DIR="${SOURCE_ROOT_DIR}/agenttools"
                    export AGENT_RESPONSE_FILE="${WORKSPACE}/agent_response.md"
                    rm -fr "$AGENT_RESPONSE_FILE" || true

                    export ISSUE_TITLE="$title"
                    export ISSUE_BODY="$body"
                    export OLLAMA_BASE_URL="http://localhost:11434"
                    #PROVIDER="ollama"
                    #MODEL="granite4:micro-h"
                    PROVIDER="gemini"
                    MODEL="gemini-2.5-flash"

                    # Setup Python virtual environment and install dependencies
                    cd "${AGENT_TOOLS_DIR}"
                    ~/.local/bin/uv venv agent_venv
                    . agent_venv/bin/activate
                    ~/.local/bin/uv pip install -r requirements.txt --link-mode=copy

                    echo "\n\n\n" >> $AGENT_LOG
                    echo "**********************************************************" >> $AGENT_LOG
                    echo "Proceeding to issue ticket analysis..."                     >> $AGENT_LOG
                    echo "**********************************************************" >> $AGENT_LOG
                    export ISSUE_TICKET_ANALYSIS="${WORKSPACE}/issue_ticket_analysis.md"
                    export SYSTEM_PROMPT_FILE="${WORKSPACE}/system_prompts/github_issue_checker.txt"
                    bash "./scripts/ongoing_printer.sh" \
                    python -m agenttools.agent \
                      --provider "$PROVIDER" \
                      --model "$MODEL" \
                      --silent \
                      --response-file "$AGENT_RESPONSE_FILE" \
                      --query "Analyse"

                    python "./scripts/clean_markdown_utf8.py" \
                        "$AGENT_RESPONSE_FILE" \
                        "$ISSUE_TICKET_ANALYSIS"

                    AGENT_RESPONSE_CONTENT=$(cat "$ISSUE_TICKET_ANALYSIS" || echo "No response generated.")
                    # If content is only whitespace or contains the default "No response generated." message, stop here
                    if ! printf '%s' "$AGENT_RESPONSE_CONTENT" | grep -q '[^[:space:]]' || printf '%s' "$AGENT_RESPONSE_CONTENT" | grep -F -q 'No response generated.'; then
                        echo "AGENT_RESPONSE_CONTENT is empty or default; skipping comment and further analysis"
                        exit 1
                    fi

                    python ${AGENT_TOOLS_DIR}/scripts/github_comment.py \
                        --repo $repository_full_name \
                        --issue $issue \
                        --body "$AGENT_RESPONSE_CONTENT" \
                        --token $GITHUB_TOKEN

                    # Check if AGENT_RESPONSE_CONTENT contains the exact marker
                    if printf '%s\n' "$AGENT_RESPONSE_CONTENT" | grep -F -q '[TICKET IS CLEAR]'; then
                        echo "Analysis: [TICKET IS CLEAR]"
                    else
                        echo 'Analysing GitHub issue completed. [TICKET IS NOT CLEAR]'
                        exit 0
                    fi

                    echo "\n\n\n" >> $AGENT_LOG
                    echo "**********************************************************" >> $AGENT_LOG
                    echo "Proceeding to middlewaresw analysis..."                     >> $AGENT_LOG
                    echo "**********************************************************" >> $AGENT_LOG
                    export ISSUE_TICKET_FOR_MIDDLEWARESW=$ISSUE_TICKET_ANALYSIS
                    bash ${AGENT_TOOLS_DIR}/scripts/run_agent_component.sh \
                        middlewaresw \
                        --issue "$issue" \
                        --repo $repository_full_name \
                        --provider "$PROVIDER" \
                        --model "$MODEL"


                    echo "\n\n\n" >> $AGENT_LOG
                    echo "**********************************************************" >> $AGENT_LOG
                    echo "Proceeding to mwclientwithgui analysis..."                  >> $AGENT_LOG
                    echo "**********************************************************" >> $AGENT_LOG
                    export ISSUE_TICKET_FOR_MWCLIENTWITHGUI=$ISSUE_TICKET_ANALYSIS
                    bash ${AGENT_TOOLS_DIR}/scripts/run_agent_component.sh \
                        mwclientwithgui \
                        --issue "$issue" \
                        --repo $repository_full_name \
                        --provider "$PROVIDER" \
                        --model "$MODEL"


                    echo "\n\n\n" >> $AGENT_LOG
                    echo "**********************************************************" >> $AGENT_LOG
                    echo "Proceeding to integration_testing analysis..."              >> $AGENT_LOG
                    echo "**********************************************************" >> $AGENT_LOG
                    export ISSUE_TICKET_FOR_INTEGRATION_TESTING=$ISSUE_TICKET_ANALYSIS
                    bash ${AGENT_TOOLS_DIR}/scripts/run_agent_component.sh \
                        integration_testing \
                        --issue "$issue" \
                        --repo $repository_full_name \
                        --provider "$PROVIDER" \
                        --model "$MODEL"

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
                artifacts: 'middlewaresw_analysis.md',
                fingerprint: true,
                allowEmptyArchive: true
            )
            archiveArtifacts(
                artifacts: 'mwclientwithgui_analysis.md',
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
