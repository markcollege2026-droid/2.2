"""Monetary helpers.

Design rule for this package: money is *only* ever an ``int`` number of minor
units (cents).  Floats are rejected at the boundary rather than silently
rounded, because ``0.1 + 0.2 != 0.3`` is exactly the class of bug that loses
customer money.
"""

from __future__ import annotations

from decimal import Decimal, InvalidOperation
from typing import Union

MAX_AMOUNT_CENTS = 10**15  # ~ $10 trillion: guards against nonsense magnitudes


class AmountError(ValueError):
    """Raised when an amount cannot be represented as positive integer cents."""


def validate_amount_cents(value: object) -> int:
    """Return ``value`` as a positive int of cents or raise :class:`AmountError`.

    Rejected inputs
    ---------------
    * anything that is not an ``int`` (floats, ``Decimal``, strings, ``bool``)
    * ``bool`` (``isinstance(True, int)`` is ``True`` in Python - a classic trap)
    * zero and negative amounts
    * magnitudes above :data:`MAX_AMOUNT_CENTS`
    """
    # bool first: bool is a subclass of int, so it would otherwise pass.
    if isinstance(value, bool):
        raise AmountError("amount must be an integer number of cents, not bool")
    if not isinstance(value, int):
        raise AmountError(
            f"amount must be an integer number of cents, got {type(value).__name__}"
        )
    if value <= 0:
        raise AmountError(f"amount must be > 0 cents, got {value}")
    if value > MAX_AMOUNT_CENTS:
        raise AmountError(f"amount exceeds maximum supported value ({MAX_AMOUNT_CENTS})")
    return value


def format_cents(amount_cents: int) -> str:
    """Render cents as a decimal string for humans, e.g. ``-1250 -> '-12.50'``."""
    if not isinstance(amount_cents, int) or isinstance(amount_cents, bool):
        raise AmountError("format_cents requires an int")
    sign = "-" if amount_cents < 0 else ""
    whole, frac = divmod(abs(amount_cents), 100)
    return f"{sign}{whole}.{frac:02d}"


def parse_amount_to_cents(text: Union[str, Decimal]) -> int:
    """Parse a human-entered decimal string into cents without binary floats.

    ``"12.345"`` is rejected (more precision than a cent) instead of being
    silently truncated.  Uses :class:`~decimal.Decimal`, never ``float``.
    """
    try:
        dec = Decimal(str(text).strip())
    except (InvalidOperation, ValueError) as exc:  # pragma: no cover - defensive
        raise AmountError(f"cannot parse amount {text!r}") from exc
    if not dec.is_finite():
        raise AmountError(f"cannot parse amount {text!r}")
    scaled = dec * 100
    if scaled != scaled.to_integral_value():
        raise AmountError(f"amount {text!r} has sub-cent precision")
    return validate_amount_cents(int(scaled))
