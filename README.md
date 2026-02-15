dbpf-grep
=========

*A command-line search tool for DBPF file contents of SC4, modeled after [grep](https://www.gnu.org/software/grep/manual/grep.html).*

It is useful for locating a specific TGI in a large Plugins folder very quickly, for debugging load order problems, and for getting a quick overview of the contents of multiple files.

<img width="689" height="562" alt="demo" src="https://github.com/user-attachments/assets/2405c734-06a3-4dcc-9fa3-ca44b2fcc405" />

## Installation

- Prerequisites: Python 3.x
- Add `bin` or `bin-windows` to your PATH.

## Usage

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
