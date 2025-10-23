#!/usr/bin/env bash
# Usage: run_agent_component.sh <component> [--issue <num>] [--repo <owner/repo>] [--ticket-file <path>]
# Components: middlewaresw, mwclientwithgui, integration
# Environment overrides supported via exports: WORKSPACE, SOURCE_ROOT_DIR, PROVIDER, MODEL, GITHUB_TOKEN, issue, repository_full_name
# The script will run the agenttools agent with the appropriate SYSTEM_PROMPT_FILE and ISSUE_TICKET_FOR_* env vars,
# then process the generated agent_response.md, check for git diffs in the relevant repo, and create a PR/comment if changes exist.

set -euo pipefail

PROGNAME=$(basename "$0")

if [ $# -lt 1 ]; then
  echo "Usage: $PROGNAME <component> [--issue <num>] [--repo <owner/repo>] [--ticket-file <path>]"
  exit 2
fi

COMPONENT=$1
shift

# defaults (can be overridden by env or flags)
WORKSPACE=${WORKSPACE:-$PWD}
SOURCE_ROOT_DIR=${SOURCE_ROOT_DIR:-$PWD}
PROVIDER=${PROVIDER:-gemini}
MODEL=${MODEL:-"gemini-2.5-flash"}
GITHUB_TOKEN=${GITHUB_TOKEN:-}
issue=${issue:-123}
repository_full_name=${repository_full_name:-"owner/repo"}

# flags
while [[ $# -gt 0 ]]; do
  case "$1" in
    --issue)
      issue="$2"; shift 2;;
    --repo)
      repository_full_name="$2"; shift 2;;
    --ticket-file)
      ISSUE_TICKET_ANALYSIS="$2"; shift 2;;
    --provider)
      PROVIDER="$2"; shift 2;;
    --model)
      MODEL="$2"; shift 2;;
    --help)
      echo "Usage: $PROGNAME <component> [--issue <num>] [--repo <owner/repo>] [--ticket-file <path>] [--provider <provider>] [--model <model>]"; exit 0;;
    *)
      echo "Unknown arg: $1"; exit 2;;
  esac
done

# set default ticket file name if not provided
ISSUE_TICKET_ANALYSIS=${ISSUE_TICKET_ANALYSIS:-issue_ticket_analysis.md}

# ensure agenttools virtualenv exists (best-effort - assumes already bootstrapped in CI as in pipeline)
# Activate if available
if [ -d "$SOURCE_ROOT_DIR/agenttools/agent_venv" ]; then
  # shellcheck disable=SC1090
  source "$SOURCE_ROOT_DIR/agenttools/agent_venv/bin/activate"
fi

# helper to run agent for a given prompt and repo
run_component() {
  local component="$1"
  local prompt_file="$2"
  local ticket_env_var="$3"
  local git_repo_dir="$4"
  local git_remote_repo_url="$5"

  echo "Running agent for component: $component"

  rm -f "$WORKSPACE/agent_response.md" || true
  export "$ticket_env_var"="$WORKSPACE/$ISSUE_TICKET_ANALYSIS"
  export SYSTEM_PROMPT_FILE="$prompt_file"

  # run the agent (the original pipeline used an ongoing_printer.sh wrapper)
  if [ -x "$SOURCE_ROOT_DIR/testing/scripts/ongoing_printer.sh" ]; then
    bash "$SOURCE_ROOT_DIR/testing/scripts/ongoing_printer.sh" \
        python -m agenttools.agent --provider "$PROVIDER" --silent --model "$MODEL" --query "Analyse"
  else
    python -m agenttools.agent --provider "$PROVIDER" --silent --model "$MODEL" --query "Analyse"
  fi

  python "$SOURCE_ROOT_DIR/testing/scripts/clean_markdown_utf8.py" "agent_response.md" "$WORKSPACE/${component}_analysis.md"

  # detect git diff
  GIT_DIFF=$(git -C "$git_repo_dir" diff || true)
  AGENT_RESPONSE_CONTENT=$(cat "$WORKSPACE/${component}_analysis.md" || echo "No response generated.")

  if [ -n "$GIT_DIFF" ]; then
    BRANCH_NAME="issue_${issue}_${component}_updates"
    TITLE="${component} updates for issue #${issue}"

    # create PR in the remote repository using provided scripts
    response=$(python "$SOURCE_ROOT_DIR/testing/scripts/github_pr.py" \
      --local \
      --git-dir "$git_repo_dir" \
      --repo "$git_remote_repo_url" \
      --head "$BRANCH_NAME" \
      --base "main" \
      --title "$TITLE" \
      --body "Automated updates for ${component} based on issue #${issue}." \
      --commit-message "Commit ${component} updates for issue #${issue}" \
      --token "$GITHUB_TOKEN" || true)

    echo "Created PR response: $response" >> "$WORKSPACE/agent_log.txt" || true

    BRANCH_URL=$(printf '%s\n' "$response" | sed -n 's/^[[:space:]]*Branch URL:[[:space:]]*//p' | head -n1 || true)
    if [ -n "$BRANCH_URL" ]; then
      echo "Found Branch URL: $BRANCH_URL" >> "$WORKSPACE/agent_log.txt" || true
      AGENT_RESPONSE_CONTENT=$(printf '%s\n\n**See branch [%s](%s)**\n' "$AGENT_RESPONSE_CONTENT" "$BRANCH_NAME" "$BRANCH_URL")
    else
      echo "No Branch URL found in response" >> "$WORKSPACE/agent_log.txt" || true
    fi
  else
    echo "No git diff for $component" >> "$WORKSPACE/agent_log.txt" || true
  fi

  python "$SOURCE_ROOT_DIR/scripts/github_comment.py" --repo "$repository_full_name" --issue "$issue" --body "$AGENT_RESPONSE_CONTENT" --token "$GITHUB_TOKEN" || true
}

# dispatch
case "$COMPONENT" in
  middlewaresw)
    run_component "middlewaresw" "${WORKSPACE}/system_prompts/middlewaresw_developer.txt" "ISSUE_TICKET_FOR_MIDDLEWARESW" "${SOURCE_ROOT_DIR}/middlewaresw" "https://github.com/jnertl/middlewaresw.git"
    ;;
  mwclientwithgui)
    run_component "mwclientwithgui" "${WORKSPACE}/system_prompts/mwclientwithgui_developer.txt" "ISSUE_TICKET_FOR_MWCLIENTWITHGUI" "${SOURCE_ROOT_DIR}/mwclientwithgui" "https://github.com/jnertl/mwclientwithgui.git"
    ;;
  integration)
    run_component "integration_testing" "${WORKSPACE}/system_prompts/integration_testing.txt" "ISSUE_TICKET_FOR_INTEGRATION_TESTING" "${SOURCE_ROOT_DIR}/testing" "https://github.com/jnertl/testing.git"
    ;;
  *)
    echo "Unknown component: $COMPONENT"; exit 2;;
esac

# done
exit 0
