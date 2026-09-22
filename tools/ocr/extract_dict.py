"""Write the recognizer's character table out of the official inference.yml.

One character per line, UTF-8, in the order the model emits them. The model's class count is
`1 + len(dict) + 1`: index 0 is the CTC blank and the last index is the space that PaddleOCR
appends when `use_space_char` is on, both added by CTCLabelDecode rather than listed in the dict.
"""

import argparse
import sys

import yaml


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--yml", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    document = yaml.safe_load(open(args.yml, encoding="utf-8"))
    characters = (document.get("PostProcess") or {}).get("character_dict")
    if not characters:
        sys.exit(f"no PostProcess.character_dict in {args.yml}")

    with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
        for character in characters:
            handle.write(f"{character}\n")

    sizes = {len(c) for c in characters}
    print(f"{args.out}: {len(characters)} characters, code point lengths {sorted(sizes)}")
    print(f"  first {characters[:6]}")
    print(f"  last  {[hex(ord(c)) for c in characters[-4:]]}")
    print(f"  classes the model should have: {len(characters) + 2}")


if __name__ == "__main__":
    main()
