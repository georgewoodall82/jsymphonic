#!/usr/bin/env python3
"""
Dump and check the files of an OMGAUDIO database written by SonicStage or JSymphonic.

Usage:
    omgaudio_dump.py classes FILE            list the classes of a file and the first elements
    omgaudio_dump.py tree OMGAUDIO ID [N]    show the groups of tree ID (hexadecimal, e.g. 02, 36, 2D)
    omgaudio_dump.py tracks OMGAUDIO [N]     show the first N elements of 04CNTINF.DAT
    omgaudio_dump.py ids OMGAUDIO            compare the content IDs of the OMA files, 05CIDLST, EXCNTINF, EXCNTMTA
    omgaudio_dump.py mountlog OMGAUDIO       check A_WM/AFMNTLOG.DAT like the NW-A82x firmware does (Simple Mode or not)
    omgaudio_dump.py diff OMGAUDIO1 OMGAUDIO2  list the files that differ, and for each, the classes and elements

All the files share the same container, see docs/reverse-engineering/02-file-formats.md.
Run it with `python3 -I` if the folder you run it from is not trusted.
"""
import glob
import os
import sys
import time


def be(data, offset, length):
    return int.from_bytes(data[offset:offset + length], 'big')


def read_classes(data):
    """Return [(class name, header bytes, [elements])] for a database file."""
    count = data[8]
    classes = []
    for i in range(count):
        description = 0x10 + i * 0x10
        name = data[description:description + 4].decode('latin1')
        offset = be(data, description + 4, 4)
        header = data[offset:offset + 0x10]
        number = be(data, offset + 4, 2)
        length = be(data, offset + 6, 2)
        elements = [data[offset + 0x10 + k * length: offset + 0x10 + (k + 1) * length] for k in range(number)]
        classes.append((name, header, elements))
    return classes


def read_tags(element, start):
    """Read the tags (4 characters, 2 bytes of encoding, UTF-16BE text, 0x80 bytes in all) of an element."""
    tags = []
    position = start
    while position + 6 <= len(element) and element[position:position + 4].isalnum():
        value = element[position + 6:position + 0x80].decode('utf-16-be', 'replace').rstrip('\0')
        tags.append((element[position:position + 4].decode(), value))
        position += 0x80
    return tags


def load(path):
    with open(path, 'rb') as f:
        return f.read()


def find(directory, name):
    for entry in os.listdir(directory):
        if entry.lower() == name.lower():
            return os.path.join(directory, entry)
    return os.path.join(directory, name)


def cmd_classes(path, limit=3):
    data = load(path)
    print('%s: %d bytes, table %r, %d class(es)' % (path, len(data), data[:4].decode('latin1'), data[8]))
    for name, header, elements in read_classes(data):
        print('  class %s: %d elements of %d bytes' % (name, len(elements), len(elements[0]) if elements else be(header, 6, 2)))
        for number, element in enumerate(elements[:limit], 1):
            print('    #%d %s' % (number, element[:0x30].hex(' ', 4)))


def cmd_tree(directory, tree_id, limit=20):
    tree = load(find(directory, '01TREE%s.DAT' % tree_id.upper()))
    ginf = load(find(directory, '03GINF%s.DAT' % tree_id.upper()))
    groups = read_classes(tree)[0][2]
    titles = read_classes(tree)[1][2]
    infos = read_classes(ginf)[0][2]
    print('tree %s: %d groups (%d active), %d titles' % (tree_id, len(groups), be(read_classes(tree)[0][1], 8, 4), len(titles)))
    for group in groups[:limit]:
        group_id, level, first = be(group, 0, 2), group[2], be(group, 4, 2)
        info = infos[group_id - 1] if 0 < group_id <= len(infos) else b''
        print('  group %3d level %d first title %4d key %10d %s' % (group_id, level, first, be(info, 8, 4), read_tags(info, 0x10)))
    ids = [be(t, 0, 2) for t in titles]
    print('  title IDs: %s%s' % (ids[:30], ' ...' if len(ids) > 30 else ''))


def cmd_tracks(directory, limit=5):
    data = load(find(directory, '04CNTINF.DAT'))
    for number, element in enumerate(read_classes(data)[0][2][:limit], 1):
        print('#%d protection %s properties %s length %d ms' % (number, element[2:4].hex(), element[4:8].hex(), be(element, 8, 4)))
        for tag, value in read_tags(element, 0x10):
            print('    %s %s' % (tag, value))


def oma_content_id(path):
    with open(path, 'rb') as f:
        data = f.read(0x40000)
    size = ((data[6] & 0x7f) << 21) | ((data[7] & 0x7f) << 14) | ((data[8] & 0x7f) << 7) | (data[9] & 0x7f)
    ea3 = data[10 + size:10 + size + 0x30]
    if ea3[:3] != b'EA3':
        return None
    return ea3[8:0x20]


def cmd_ids(directory):
    omas = {}
    for path in glob.glob(os.path.join(directory, '10[Ff]*', '*.[Oo][Mm][Aa]')):
        omas[int(os.path.basename(path)[:-4], 16) & 0xffff] = oma_content_id(path)
    cidlst = read_classes(load(find(directory, '05CIDLST.DAT')))[0][2]
    print('%d OMA files, %d elements in 05CIDLST' % (len(omas), len(cidlst)))
    sources = {'05CIDLST': cidlst}
    for name, table in (('EXCNTINF.DAT', 'ARCI'), ('EXCNTMTA.DAT', 'EXCM')):
        path = find(find(directory, 'A_WM'), name)
        if os.path.exists(path):
            sources[name] = read_classes(load(path))[0][2]
    for name, elements in sources.items():
        wrong = [i + 1 for i, e in enumerate(elements) if i + 1 in omas and omas[i + 1] is not None and e[:0x18] != omas[i + 1]]
        print('  %-13s %d elements, %d differ from the OMA files%s' % (name, len(elements), len(wrong), (' (e.g. title %d)' % wrong[0]) if wrong else ''))
    values = [omas[i].hex() for i in sorted(omas) if omas[i] is not None]
    print('  distinct content IDs in the OMA files: %d of %d, all zero: %d' % (len(set(values)), len(values), sum(1 for v in values if int(v, 16) == 0)))


def bcd_time(raw):
    digits = raw[:7].hex()
    return (int(digits[0:4]), int(digits[4:6]), int(digits[6:8]), int(digits[8:10]), int(digits[10:12]), int(digits[12:14]))


def cmd_mountlog(directory):
    """Check what the NW-A82x firmware checks in A_WM/AFMNTLOG.DAT before it trusts the database."""
    log_path = find(find(directory, 'A_WM'), 'AFMNTLOG.DAT')
    if not os.path.exists(log_path):
        print('AFMNTLOG.DAT is missing -> SIMPLE MODE')
        return
    log = load(log_path)
    classes = read_classes(log)
    dtbl, mlbl = classes[0][2][0], classes[1][2]
    problems = []
    for index, name in enumerate(('01TREE01.DAT', '04CNTINF.DAT')):
        stored = bcd_time(dtbl[index * 8:index * 8 + 8])
        mtime = time.localtime(os.stat(find(directory, name)).st_mtime)
        actual = (mtime.tm_year, mtime.tm_mon, mtime.tm_mday, mtime.tm_hour, mtime.tm_min, mtime.tm_sec)
        print('%-13s stored %s file %s %s' % (name, stored, actual, 'MATCH' if stored == actual else 'DIFFERENT'))
        if stored != actual:
            problems.append(name)
    basic = mlbl[0]
    tree_time = bcd_time(dtbl[:8])
    print('MLBL element 1 (BasicTree): flag %d, time %s %s' % (basic[0], bcd_time(basic[8:16]), 'MATCH' if basic[0] == 1 and bcd_time(basic[8:16]) == tree_time else 'DIFFERENT'))
    if basic[0] != 1 or bcd_time(basic[8:16]) != tree_time:
        problems.append('BasicTree')
    for number, element in enumerate(mlbl, 1):
        if any(element):
            print('MLBL element %d: version %d.%d time %s' % (number, element[0], element[1], bcd_time(element[8:16])))
    print('-> SIMPLE MODE (%s)' % ', '.join(problems) if problems else '-> FULL MODE')


def cmd_diff(first, second):
    names = lambda d: sorted(os.path.relpath(os.path.join(r, f), d) for r, _, fs in os.walk(d) for f in fs)
    a, b = names(first), names(second)
    for name in sorted(set(a) - set(b)):
        print('only in first:  %s' % name)
    for name in sorted(set(b) - set(a)):
        print('only in second: %s' % name)
    for name in sorted(set(a) & set(b)):
        x, y = load(os.path.join(first, name)), load(os.path.join(second, name))
        if x == y:
            continue
        print('changed: %s (%d -> %d bytes)' % (name, len(x), len(y)))
        if name.upper().endswith('.DAT') and x[:4] == y[:4] and len(x) > 0x30 and len(y) > 0x30 and x[8] == y[8] and x[8] < 8:
            try:
                for (n1, h1, e1), (n2, h2, e2) in zip(read_classes(x), read_classes(y)):
                    different = [k + 1 for k in range(min(len(e1), len(e2))) if e1[k] != e2[k]]
                    print('    class %s: %d vs %d elements, %d differ %s' % (n1, len(e1), len(e2), len(different), different[:8]))
            except Exception:
                pass


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 1
    command, args = argv[1], argv[2:]
    if command == 'classes':
        cmd_classes(args[0])
    elif command == 'tree':
        cmd_tree(args[0], args[1], int(args[2]) if len(args) > 2 else 20)
    elif command == 'tracks':
        cmd_tracks(args[0], int(args[1]) if len(args) > 1 else 5)
    elif command == 'ids':
        cmd_ids(args[0])
    elif command == 'mountlog':
        cmd_mountlog(args[0])
    elif command == 'diff':
        cmd_diff(args[0], args[1])
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
