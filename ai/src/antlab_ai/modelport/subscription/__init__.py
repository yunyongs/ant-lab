"""Subscription-CLI routes (CLAUDE_CLI, CODEX_CLI): one subprocess per attempt, off unless a gate is wired."""

from .claude_cli import ClaudeCliAdapter
from .codex_cli import CodexCliAdapter
from .common import CliConfig, SubscriptionGate

__all__ = ["ClaudeCliAdapter", "CliConfig", "CodexCliAdapter", "SubscriptionGate"]
