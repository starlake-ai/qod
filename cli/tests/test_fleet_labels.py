import pytest

from qod_cli.fleet_labels import labels_from, parse_labels


def test_parse_strips_and_splits_on_the_first_equals():
    assert parse_labels(["disktype=ssd", " tier = highmem "]) == {"disktype": "ssd", "tier": "highmem"}
    assert parse_labels(["a="]) == {"a": ""}
    assert parse_labels(["", "  "]) == {}


def test_labels_from_flags_win_over_env():
    assert labels_from(["x=1"], "a=b") == {"x": "1"}
    assert labels_from(None, "a=b,,c=d,") == {"a": "b", "c": "d"}
    assert labels_from([], "a=b") == {"a": "b"}
    assert labels_from(None, "") == {}
    assert labels_from(None, None) == {}


@pytest.mark.parametrize("item", [
    "nokey", "a=1=2", "-bad=x", "a=-x", "=v", "a/b/c=v", "Ex.com/a=v", "/a=v", "p/=v",
    "a=" + "x" * 64, "x" * 64 + "=v", "a b=v", "a=b c",
])
def test_broken_rules_raise(item):
    with pytest.raises(ValueError):
        parse_labels([item])


def test_error_names_the_item():
    with pytest.raises(ValueError, match="nokey"):
        parse_labels(["nokey"])
    with pytest.raises(ValueError, match="'a'"):
        parse_labels(["a=b=c"])


def test_duplicate_key_and_count_limit():
    with pytest.raises(ValueError, match="duplicate"):
        parse_labels(["a=1", "a=2"])
    assert len(parse_labels([f"k{i}=v" for i in range(64)])) == 64
    with pytest.raises(ValueError, match="at most 64"):
        parse_labels([f"k{i}=v" for i in range(65)])


def test_valid_shapes():
    assert parse_labels(["example.com/Zone_1=A.b-c", "a=" + "x" * 63]) == {"example.com/Zone_1": "A.b-c", "a": "x" * 63}
