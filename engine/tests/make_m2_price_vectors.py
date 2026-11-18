"""Writes the Python-owned `m2-sb6-v1` price vectors to stdout; run by hand, review as data.

    uv run --frozen python tests/make_m2_price_vectors.py \
        > ../src/test/resources/engine/parity/m2-prices-v1.csv

Columns: seed,length,turn,asset,month,price. The months come from `stationary.generate` on the
months of `inputs-v1.csv` (base date 2026-01-02); the price of turn t is the initial won price times
the exact decimal product of the factors of turns 1..t, rounded half up to a whole won once.
The Java test `M2PriceVectorsTest` reads the file and recomputes every price with MoneyMath.priceAt.
"""

from parity_prices import BASE_DATE, PRICES_HEADER, exact_value, load_inputs, won

from antlab_engine.bootstrap.stationary import generate

SEEDS = [
    0,
    1,
    2,
    3,
    5,
    7,
    11,
    13,
    14,
    15,
    19,
    42,
    99,
    1234567,
    -1,
    -2,
    -(2**63),
    2**63 - 1,
    987654321,
    31337,
]
LENGTHS = (12, 24, 60)


def render() -> str:
    inputs = load_inputs()
    lines = [PRICES_HEADER]
    for seed in SEEDS:
        for length in LENGTHS:
            path = generate(inputs.months, BASE_DATE, length, seed).months
            for turn in range(1, length + 1):
                for asset in inputs.assets:
                    price = won(exact_value(inputs, asset, list(path[:turn])))
                    lines.append(f"{seed},{length},{turn},{asset},{path[turn - 1]:%Y-%m},{price}")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    print(render(), end="")
