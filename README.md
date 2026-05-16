*Command-line tools for working with DBPF files of SC4.*


- `dbpf-grep`: A search tool for DBPF file contents, modeled after [grep](https://www.gnu.org/software/grep/manual/grep.html).
- `dbpf-concat`: Concatenate multiple DBPF files into one.
- `dbpf-text`: Convert a DBPF file to a human-readable text format (e.g. for diffing).

## Installation

- Prerequisites: Python 3.x
- Download and extract the [latest release](https://github.com/memo33/dbpf-grep/releases/latest) and add `bin` to your PATH.

## `dbpf-grep`

A search tool for DBPF file contents, modeled after [grep](https://www.gnu.org/software/grep/manual/grep.html).

It is useful for locating a specific TGI in a large Plugins folder very quickly, for debugging load order problems, and for getting a quick overview of the contents of multiple files.

<img width="689" height="562" alt="demo01" src="https://github.com/user-attachments/assets/2405c734-06a3-4dcc-9fa3-ca44b2fcc405" />

Synopsis: `dbpf-grep [options] [--] [files...]`

Print the TGI index of DBPF files, optionally filter for TGIs matching a pattern

If multiple files are scanned, the name of each matching file is printed as well.

```
examples:
    dbpf-grep file.dat                          # print the whole TGI index
    dbpf-grep -e Exemplar -e S3D file.dat       # print TGIs of Exemplars and S3Ds
    dbpf-grep --regexp '030.00\b' file.dat      # print only matching TGIs
    dbpf-grep -l                                # print all DBPF file names of current directory recursively
    dbpf-grep --regexp '10000002' folder        # search for an ID in a folder
    dbpf-grep -e LText | less -R                # use a pager for long output

positional arguments:
  files                 Names of DBPF files or directories to scan

options:
  -h, --help            show this help message and exit
  -e, --regexp pattern  Print only matching TGIs (case-sensitive regular expression).
  -i, --ignore-case     Ignore case distinctions in patterns.
  -l, --name-only       Only print the names of matching files, no TGIs.
  --no-color            Do not use colors.
```

## `dbpf-concat`

Synopsis: `dbpf-concat [options] [DBPF input files]`

Concatenate multiple DBPF files into one.

The files are taken in the order in which they are specified on the command line.
Later files overwrite TGIs of earlier files, but if there are duplicate TGIs within a single file, that is an error by default.

Using the `--append` option allows patching an existing DBPF file in place.

```
Examples:
  dbpf-concat -o output.dat input1.dat input2.dat
  dbpf-concat -o file.dat --append input1.dat input2.dat

Help options:
  --usage            Print usage and exit
  -h, -help, --help  Print help message and exit

Other options:
  -o, --output file         Output file
  --append                  If the output file already exists, append to it instead of overwriting it.
  --discard-duplicate-tgis  If an input file contains duplicate TGIs, keep only the first occurence instead of raising an error.
  --silent                  Suppress non-error output.
```

## `dbpf-text`

Synopsis: `dbpf-text [options] [DBPF input file]`

Convert a DBPF file to a human-readable text format.

This is especially useful for comparing DBPF files with git.

<img width="881" height="272" alt="demo02" src="https://github.com/user-attachments/assets/7140a627-3f80-4d81-8a20-c149325319ba" />

```
Examples:
  dbpf-text input.dat > output.txt

Directory files are ignored.
The hash algorithm used is rapidhashV3.

Help options:
  --usage            Print usage and exit
  -h, -help, --help  Print help message and exit

Other options:
  --decompressed  Unpack QFS-compressed entries. This is much slower, but avoids hash differences that are only caused by the QFS compression.
  --sorted        Sort entries by TGI instead of keeping the original order.
```
