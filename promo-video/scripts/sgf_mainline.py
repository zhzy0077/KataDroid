"""Read SGF syntax and retain factual main-line moves; discard commentary."""
import re

TOKEN = re.compile(r"\s*([();]|[A-Za-z]+|\[(?:\\[\s\S]|[^\]\\])*\])")


def parse(source):
    tokens = []
    at = 0
    while at < len(source):
        if not source[at:].strip():
            break
        match = TOKEN.match(source, at)
        if not match:
            raise ValueError("Invalid SGF syntax near character " + str(at))
        tokens.append(match.group(1))
        at = match.end()
    cursor = 0

    def tree():
        nonlocal cursor
        if cursor >= len(tokens) or tokens[cursor] != "(":
            raise ValueError("Expected an SGF game tree")
        cursor += 1
        sequence = []
        while cursor < len(tokens) and tokens[cursor] == ";":
            cursor += 1
            node = {}
            while cursor < len(tokens) and tokens[cursor].isalpha():
                name = tokens[cursor].upper()
                cursor += 1
                values = []
                while cursor < len(tokens) and tokens[cursor].startswith("["):
                    raw = tokens[cursor][1:-1]
                    values.append(re.sub(r"\\(?:\r\n|\r|\n)|\\([\s\S])", lambda m: m.group(1) or "", raw))
                    cursor += 1
                if not values:
                    raise ValueError("SGF property has no values")
                node[name] = values
            sequence.append(node)
        variations = []
        while cursor < len(tokens) and tokens[cursor] == "(":
            variations.append(tree())
        if cursor >= len(tokens) or tokens[cursor] != ")":
            raise ValueError("Unclosed SGF game tree")
        cursor += 1
        if not sequence:
            raise ValueError("Empty SGF game tree")
        return sequence, variations

    result = []
    while cursor < len(tokens):
        result.append(tree())
    return result


def mainline(game):
    sequence, variations = game
    return sequence + (mainline(variations[0]) if variations else [])


def factual_mainline(source):
    nodes = mainline(parse(source)[0])
    root = {k: v for k, v in nodes[0].items()
            if k in {"GM", "FF", "SZ", "RU", "KM", "PB", "PW", "BR", "WR", "DT", "RO", "RE", "EV", "AB", "AW", "AE", "PL"}}
    root.update(GM=["1"], FF=["4"], CA=["UTF-8"], GN=["AlphaGo–Lee Sedol · Game 4"])
    moves = [{color: node[color]} for node in nodes for color in ("B", "W") if color in node]

    def emit(node):
        return ";" + "".join(key + "".join("[" + value.replace("\\", "\\\\").replace("]", "\\]") + "]" for value in values)
                              for key, values in node.items())

    return "(" + emit(root) + "\n" + "\n".join(emit(move) for move in moves) + ")\n", moves
