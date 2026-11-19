"""Subscription-CLI routes (CLAUDE_CLI, CODEX_CLI): one subprocess per attempt, off unless a gate is wired."""

from .claude_cli import ClaudeCliAdapter
from .codex_cli import CodexCliAdapter
from .common import CliConfig, SubscriptionGate
from .gate import BatchGate, LedgeredPort, OwnerProof, verify_owner
from .ledger import BatchLedger, BatchLock
from .settings import SubscriptionSettings, SubscriptionSettingsError
from .usage import CommandUsageReader, UsageReader, UsageSnapshot, UsageUnavailable

__all__ = [
    "BatchGate",
    "BatchLedger",
    "BatchLock",
    "ClaudeCliAdapter",
    "CliConfig",
    "CodexCliAdapter",
    "CommandUsageReader",
    "LedgeredPort",
    "OwnerProof",
    "SubscriptionGate",
    "SubscriptionSettings",
    "SubscriptionSettingsError",
    "UsageReader",
    "UsageSnapshot",
    "UsageUnavailable",
    "verify_owner",
]
