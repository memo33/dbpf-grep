#!/usr/bin/env python3
# Print the TGI index of a DBPF file.
import sys
import argparse
import re

_prog_usage = r"dbpf-grep [options] [--] [files...]"
_prog_description = r"""
dbpf-grep - Print the TGI index of DBPF files, optionally filter for TGIs matching a pattern

Example:
    dbpf-grep file.dat                          # print the whole TGI index
    dbpf-grep -i --regexp '030.00\b' file.dat   # print only matching TGIs
    dbpf-grep -e Exemplar -e S3D file.dat       # print TGIs of Exemplars and S3Ds

If multiple files are passed, the name of each matching file is printed as well.

Example:
    find -type f -iregex '.*\.\(dat\|sc4model\|sc4desc\|sc4lot\|sc4\)$' -print0 | \
            xargs -0 dbpf-grep -i --regexp '10000002' --
""".strip()


class colors:
    GRAY = '\033[90m'
    RED = '\033[91m'
    GREEN = '\033[92m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    PURPLE = '\033[95m'
    CYAN = '\033[96m'
    ENDC = '\033[0m'


exemplar_group_labels = {
    0x2821ed93: "Road",
    0xa92a02ea: "Street",
    0xcbe084cb: "One-Way Road",
    0xcb730fac: "Avenue",
    0xa8434037: "Highway",
    0xebe084d1: "Ground Highway",
    0x6be08658: "Dirtroad",
    0xe8347989: "Rail",
    0x2b79dffb: "Lightrail",
    0xebe084c2: "Monorail",
    0x8a15f3f2: "Subway",
    0x088e1962: "Power Pole",
    0x89ac5643: "T21",
}

fsh_group_labels = {
    0x1abe787d: "Misc",
    0x0986135e: "Base/Overlay Texture",
    0x2BC2759a: "Shadow Mask",
    0x2a2458f9: "Animation Sprites (Props",
    0x49a593e7: "Animation Sprites (Non Props",
    0x891b0e1a: "Terrain/Foundation",
    0x46a006b0: "UI Image",
}

tgi_labels = {
    0x5ad0e817: ("S3D", colors.CYAN, {0xbadb57f1: "Maxis"}),
    0x7ab50e44: ("FSH", colors.GRAY, fsh_group_labels),
    0xe86b1eef: ("Directory", None, None),
    0x05342861: ("Cohort", colors.GREEN, {0xb03697d1: "Patch"}),
    0x6534284a: ("Exemplar", colors.GREEN, exemplar_group_labels),
    0x296678f7: ("SC4Path", colors.BLUE, {0x69668828: "2D", 0xa966883f: "3D"}),
    0x856ddbac: ("PNG", None, None),
    0xca63e2a3: ("Lua", colors.YELLOW, None),
    0x2026960b: ("LText", None, {0xaa4d1933: "WAV"}),  # note that WAV is not an LText
    0x00000000: ("INI", colors.YELLOW, None),
    0x0a5bcf4b: ("RUL", colors.YELLOW, None),
    0xea5118b0: ("EffDir", colors.YELLOW, None),
}


def create_label(tgi) -> (str, str):
    values = tgi_labels.get(tgi[0])
    if values is not None:
        tlabel, color, group_labels = values
        glabel = group_labels.get(tgi[1]) if group_labels is not None else None
        label = tlabel if glabel is None else glabel if glabel == "WAV" else f"{tlabel} ({glabel})"
        return label, color
    else:
        return "Unknown", None


def print_index(filename, *, patterns: list, print_filename: bool, name_only: bool, color: bool):
    logged_filename = False
    with open(filename, "rb") as file:
        # parse header
        header = file.read(60)
        signature = header[0:4]
        if signature != b"DBPF":
            raise Exception(f"Not a DBPF file: {file.name}")
        index_type = int.from_bytes(header[32:36], byteorder='little')
        if index_type != 7:
            raise Exception(f"Unsupported index type: {index_type}")
        entry_count = int.from_bytes(header[36:40], byteorder='little')
        index_offset = int.from_bytes(header[40:44], byteorder='little')
        index_length = int.from_bytes(header[44:48], byteorder='little')
        if index_length != entry_count * 20:
            raise Exception("Unexpected/invalid index data")

        # parse TGI index
        file.seek(index_offset)
        data = file.read(index_length)
        hexfmt = '08X'
        for j in range(0, index_length, 20):
            tgi = (int.from_bytes(data[j:j+4], byteorder='little'),
                   int.from_bytes(data[j+4:j+8], byteorder='little'),
                   int.from_bytes(data[j+8:j+12], byteorder='little'))
            label, label_color = create_label(tgi)
            t, g, i = [format(x, hexfmt) for x in tgi]
            line = f"T:{t} G:{g} I:{i} {label}"
            if not patterns or any(p.search(line) for p in patterns):
                if print_filename and not logged_filename:
                    if name_only:
                        print(filename)
                        break
                    else:
                        print(f"==> {filename}" if not color else f"==> {colors.PURPLE}{filename}{colors.ENDC}")
                    logged_filename = True
                if not color:
                    print(line)
                else:
                    labelc = label if label_color is None else f"{label_color}{label}{colors.ENDC}"
                    # linec = f"T:{colors.GRAY}{t}{colors.ENDC} G:{colors.GRAY}{g}{colors.ENDC} I:{colors.GRAY}{i}{colors.ENDC} {labelc}"
                    # linec = f"T:{t} G:{g} I:{i} {labelc}"
                    linec = f"{colors.GRAY}T:{colors.ENDC}{t} {colors.GRAY}G:{colors.ENDC}{g} {colors.GRAY}I:{colors.ENDC}{i} {labelc}"
                    print(linec)


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="dbpf-grep",
        usage=_prog_usage,
        description=_prog_description,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("-e", "--regexp", metavar="pattern", action='append', help="Print only matching TGIs (case-sensitive regular expression).")
    parser.add_argument("-i", "--ignore-case", action='store_true', help="Ignore case distinctions in patterns.")
    parser.add_argument("-l", "--name-only", action='store_true', help="Only print the names of matching files, no TGIs.")
    parser.add_argument("--no-color", action='store_true')  # help="todo"
    parser.add_argument("files", nargs="*", help="Names of DBPF files or directories to scan")
    args = parser.parse_args()

    patterns = [re.compile(s, re.IGNORECASE) if args.ignore_case else re.compile(s) for s in args.regexp or []]

    print_filename = len(args.files) > 1
    for filename in args.files:
        print_index(filename, patterns=patterns, print_filename=print_filename, name_only=args.name_only, color=not args.no_color)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as e:
        print(e, file=sys.stderr)
        sys.exit(1)
