import pytest

from odyn_ai.core.evidence_gate import (
    CriticResult,
    GateAction,
    adversarial_gate,
)


def test_missing_evidence_precedes_valid_low_severity_acceptance():
    result = adversarial_gate(
        CriticResult(valid=True, severity=0.0, required_evidence=("source:1",))
    )

    assert result.action is GateAction.RETRIEVE_EVIDENCE
    assert result.missing_evidence == ("source:1",)


def test_complete_evidence_allows_acceptance():
    result = adversarial_gate(
        CriticResult(valid=True, severity=0.1, required_evidence=("source:1",)),
        available_evidence=("source:1",),
    )

    assert result.action is GateAction.ACCEPT


def test_unavailable_evidence_after_retrieval_blocks_decision():
    result = adversarial_gate(
        CriticResult(valid=True, severity=0.0, required_evidence=("source:1",)),
        retrieval_attempted=True,
    )

    assert result.action is GateAction.BLOCKED_EVIDENCE
    assert result.missing_evidence == ("source:1",)


def test_invalid_critic_is_rejected_after_evidence_is_resolved():
    result = adversarial_gate(CriticResult(valid=False, severity=0.1))

    assert result.action is GateAction.REJECT


def test_high_severity_escalates():
    result = adversarial_gate(CriticResult(valid=True, severity=0.8))

    assert result.action is GateAction.ESCALATE


@pytest.mark.parametrize("severity", [-0.1, 1.1, float("nan"), float("inf")])
def test_invalid_severity_is_rejected(severity):
    with pytest.raises(ValueError):
        adversarial_gate(CriticResult(valid=True, severity=severity))


def test_empty_required_evidence_identifier_is_rejected():
    with pytest.raises(ValueError):
        adversarial_gate(CriticResult(valid=True, severity=0.0, required_evidence=(" ",)))
