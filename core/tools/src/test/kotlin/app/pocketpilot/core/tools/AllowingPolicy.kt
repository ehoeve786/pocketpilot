package app.pocketpilot.core.tools

import app.pocketpilot.core.policy.AppPolicy
import app.pocketpilot.core.policy.ConfirmationAnswer
import app.pocketpilot.core.policy.Confirmer
import app.pocketpilot.core.policy.PolicyEngine
import app.pocketpilot.core.policy.RateLimiter

/** A policy for tests that are not about policy: nothing is denied, and every confirmation is approved. */
internal fun allowingPolicy() =
    PolicyEngine(
        AppPolicy(),
        { null },
        Confirmer { _ ->
            ConfirmationAnswer.APPROVED
        },
        RateLimiter(Int.MAX_VALUE),
    )
