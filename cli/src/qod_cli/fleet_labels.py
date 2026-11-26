"""Fleet server labels: parsing and the Kubernetes-style rules the manager enforces
(FleetLabels.scala). Matching is case-sensitive."""
import re

MAX_LABELS = 64
_MAX_NAME, _MAX_VALUE, _MAX_PREFIX = 63, 63, 253
_NAME = re.compile(r"[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?")
_PREFIX = re.compile(r"[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*")
_CHARS = "alphanumeric characters, '-', '_' or '.', starting and ending with an alphanumeric"


def _problem(key: str, value: str) -> str | None:
    """The first rule the pair breaks, as a sentence naming the key (and value), or None."""
    prefix, sep, name = key.partition("/")
    if not sep:
        prefix, name = None, key
    if prefix is not None and (not prefix or len(prefix) > _MAX_PREFIX or not _PREFIX.fullmatch(prefix)):
        return f"label key '{key}' has an invalid prefix: a DNS subdomain of at most {_MAX_PREFIX} characters is required"
    if not name or len(name) > _MAX_NAME or not _NAME.fullmatch(name):
        return f"label key '{key}' has an invalid name: at most {_MAX_NAME} {_CHARS}"
    if value and (len(value) > _MAX_VALUE or not _NAME.fullmatch(value)):
        return f"label '{key}' has an invalid value '{value}': empty, or at most {_MAX_VALUE} {_CHARS}"
    return None


def parse_labels(items: list[str]) -> dict[str, str]:
    """Parse `key=value` items (split on the first `=`, both sides stripped, empty items skipped).
    Raises ValueError naming the offending item."""
    labels: dict[str, str] = {}
    for item in items:
        if not item.strip():
            continue
        key, sep, value = item.partition("=")
        if not sep:
            raise ValueError(f"'{item}' is not key=value")
        key, value = key.strip(), value.strip()
        if key in labels:
            raise ValueError(f"duplicate label key '{key}'")
        labels[key] = value
    if len(labels) > MAX_LABELS:
        raise ValueError(f"at most {MAX_LABELS} labels per server, got {len(labels)}")
    for key in sorted(labels):
        problem = _problem(key, labels[key])
        if problem:
            raise ValueError(problem)
    return labels


def labels_from(flags: list[str] | None, env: str | None) -> dict[str, str]:
    """The --label flags when any is given, else the comma-separated QOD_FLEET_LABELS value."""
    if flags:
        return parse_labels(flags)
    return parse_labels((env or "").split(","))
