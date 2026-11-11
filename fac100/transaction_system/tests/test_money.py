"""Tests for integer-cent money handling (no floating point anywhere)."""

from __future__ import annotations

import unittest

from transaction_system import AmountError, format_cents, parse_amount_to_cents, validate_amount_cents
from transaction_system.money import MAX_AMOUNT_CENTS


class TestAmountValidation(unittest.TestCase):
    CATEGORY = "integer_precision"

    def test_accepts_positive_int(self) -> None:
        self.assertEqual(validate_amount_cents(1), 1)
        self.assertEqual(validate_amount_cents(123_456_789), 123_456_789)

    def test_rejects_float(self) -> None:
        for bad in (10.0, 0.5, 1e3):
            with self.subTest(bad=bad):
                with self.assertRaises(AmountError):
                    validate_amount_cents(bad)

    def test_rejects_bool(self) -> None:
        for bad in (True, False):
            with self.subTest(bad=bad):
                with self.assertRaises(AmountError):
                    validate_amount_cents(bad)

    def test_rejects_zero_and_negative(self) -> None:
        for bad in (0, -1, -0, -10_000):
            with self.subTest(bad=bad):
                with self.assertRaises(AmountError):
                    validate_amount_cents(bad)

    def test_rejects_string_and_none(self) -> None:
        for bad in ("100", None, b"100", [100]):
            with self.subTest(bad=bad):
                with self.assertRaises(AmountError):
                    validate_amount_cents(bad)

    def test_rejects_absurd_magnitude(self) -> None:
        with self.assertRaises(AmountError):
            validate_amount_cents(MAX_AMOUNT_CENTS + 1)


class TestIntegerPrecision(unittest.TestCase):
    CATEGORY = "integer_precision"

    def test_float_arithmetic_drift_does_not_occur(self) -> None:
        # 0.1 + 0.2 == 0.30000000000000004 in binary floating point.
        total = parse_amount_to_cents("0.10") + parse_amount_to_cents("0.20")
        self.assertEqual(total, 30)
        self.assertNotEqual(0.1 + 0.2, 0.3)  # documents the hazard being avoided
        self.assertEqual(format_cents(total), "0.30")

    def test_hundred_pennies_is_exactly_one_unit(self) -> None:
        total = sum(parse_amount_to_cents("0.01") for _ in range(100))
        self.assertEqual(total, 100)
        self.assertEqual(format_cents(total), "1.00")

    def test_large_sums_stay_exact(self) -> None:
        cents = 10**14 - 1
        self.assertEqual(cents + 1, 10**14)
        self.assertEqual(format_cents(10**14 - 1), "999999999999.99")

    def test_parse_rejects_sub_cent_precision(self) -> None:
        with self.assertRaises(AmountError):
            parse_amount_to_cents("12.345")

    def test_format_cents_negative(self) -> None:
        self.assertEqual(format_cents(-1), "-0.01")
        self.assertEqual(format_cents(-1234), "-12.34")


if __name__ == "__main__":
    unittest.main()
