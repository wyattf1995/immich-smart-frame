#!/usr/bin/env python3
"""Preflight literal mutation targets without running the TouchFrame proof."""
from __future__ import annotations

import ast
from dataclasses import dataclass
from pathlib import Path
import sys


RUNNER = Path("scripts/verify-touchframe-proof.py")
# The design proof refuses the same mistakes in its own preflight, but only after the NAS queue, upload and SDK
# bootstrap (builds 19, 20, 22, 27, 28 and 38). Checking its MUTATIONS here fails the build in seconds instead.
DESIGN_RUNNER = Path("scripts/verify-touchframe-design-proof.py")


@dataclass(frozen=True)
class Target:
    name: str
    source: Path
    before: str
    after: str
    kind: str


def literal(node: ast.AST, label: str) -> str:
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    raise ValueError(f"{label} must be a string literal")


def expression(node: ast.AST, names: dict[str, Path], root: Path, label: str) -> Path:
    if isinstance(node, ast.Name):
        try:
            return names[node.id]
        except KeyError as error:
            raise ValueError(f"{label} uses unsupported name {node.id!r}") from error
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Div):
        return expression(node.left, names, root, label) / literal(node.right, label)
    raise ValueError(f"{label} uses unsupported path expression")


def assignment_map(tree: ast.Module, root: Path) -> dict[str, Path]:
    names: dict[str, Path] = {"root": root}
    for statement in tree.body:
        if not isinstance(statement, ast.Assign) or len(statement.targets) != 1:
            continue
        target = statement.targets[0]
        if not isinstance(target, ast.Name):
            continue
        try:
            names[target.id] = expression(statement.value, names, root, target.id)
        except ValueError:
            continue
    return names


def suite_sources(tree: ast.Module, names: dict[str, Path]) -> dict[str, Path]:
    for statement in tree.body:
        if not isinstance(statement, ast.Assign) or len(statement.targets) != 1:
            continue
        if not isinstance(statement.targets[0], ast.Name) or statement.targets[0].id != "suites":
            continue
        if not isinstance(statement.value, ast.Dict):
            raise ValueError("suites must be a literal dictionary")
        result: dict[str, Path] = {}
        for key, value in zip(statement.value.keys, statement.value.values, strict=True):
            name = literal(key, "suite name")
            if not isinstance(value, ast.Tuple) or not value.elts:
                raise ValueError(f"suite {name!r} must begin with a source path")
            result[name] = expression(value.elts[0], names, names["root"], f"suite {name!r}")
        return result
    raise ValueError("suites assignment not found")


def call_name(node: ast.Call) -> str | None:
    return node.func.id if isinstance(node.func, ast.Name) else None


def calls(tree: ast.Module, name: str) -> list[ast.Call]:
    return [node for node in ast.walk(tree) if isinstance(node, ast.Call) and call_name(node) == name]


def target_from_call(call: ast.Call, source: Path, kind: str, source_name_index: int | None = None, suites: dict[str, Path] | None = None) -> Target:
    if len(call.args) < 4:
        raise ValueError(f"{kind} mutation call has too few positional arguments")
    name = literal(call.args[0], f"{kind} mutation name")
    if source_name_index is not None:
        if suites is None:
            raise AssertionError("suite map required")
        suite = literal(call.args[source_name_index], f"{name} source suite")
        try:
            source = suites[suite]
        except KeyError as error:
            raise ValueError(f"{name} references unknown suite {suite!r}") from error
        before_index = source_name_index + 1
    else:
        before_index = 1
    return Target(name, source, literal(call.args[before_index], f"{name} before"), literal(call.args[before_index + 1], f"{name} after"), kind)


def function(tree: ast.Module, name: str) -> ast.FunctionDef:
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name == name:
            return node
    raise ValueError(f"helper {name!r} not found")


def literal_assignment(body: list[ast.stmt], name: str) -> str:
    for node in ast.walk(ast.Module(body=body, type_ignores=[])):
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name) and node.targets[0].id == name:
            return literal(node.value, name)
    raise ValueError(f"literal assignment {name!r} not found")


def helper_targets(tree: ast.Module, names: dict[str, Path], suites: dict[str, Path]) -> list[Target]:
    activity = function(tree, "mutate_activity_queue")
    activity_source = next(
        expression(node.value, names, names["root"], "activity source")
        for node in ast.walk(activity)
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name) and node.targets[0].id == "source"
    )
    result = [Target("activity-cancel-queue-red", activity_source, literal_assignment(activity.body, "before"), literal_assignment(activity.body, "after"), "activity")]

    calendar = function(tree, "mutate_calendar_closed")
    replacements = next(
        node.value for node in ast.walk(calendar)
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name) and node.targets[0].id == "replacements"
    )
    if not isinstance(replacements, (ast.Tuple, ast.List)):
        raise ValueError("calendar replacements must be a literal sequence")
    for index, pair in enumerate(replacements.elts, start=1):
        if not isinstance(pair, (ast.Tuple, ast.List)) or len(pair.elts) != 2:
            raise ValueError("calendar replacement must be a before/after pair")
        result.append(Target(f"calendar-closed-repository-red[{index}]", suites["calendar_repository"], literal(pair.elts[0], "calendar before"), literal(pair.elts[1], "calendar after"), "calendar"))

    packaging_source = names["packaging_manifest"]
    packaging_calls = [
        node for node in ast.walk(tree)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr == "replace"
        and isinstance(node.func.value, ast.Name) and node.func.value.id == "original"
        and len(node.args) == 2
        and all(isinstance(arg, ast.Constant) and isinstance(arg.value, str) for arg in node.args)
    ]
    if len(packaging_calls) != 1:
        raise ValueError(f"expected one literal packaging replacement, found {len(packaging_calls)}")
    result.append(Target("packaging-version-red", packaging_source, literal(packaging_calls[0].args[0], "packaging before"), literal(packaging_calls[0].args[1], "packaging after"), "packaging"))
    return result


def discover(root: Path) -> list[Target]:
    runner = root / RUNNER
    if not runner.is_file() or runner.is_symlink():
        raise ValueError(f"runner is not a regular file: {runner}")
    tree = ast.parse(runner.read_text(encoding="utf-8"), filename=str(runner))
    names = assignment_map(tree, root)
    suites = suite_sources(tree, names)
    targets = [target_from_call(call, root, "mutate", 1, suites) for call in calls(tree, "mutate")]
    if len(targets) != 48:
        raise ValueError(f"expected 48 mutate calls, found {len(targets)}")
    targets.extend(target_from_call(call, suites["repository"], "repository") for call in calls(tree, "mutate_repository_case"))
    targets.extend(target_from_call(call, names["bridge_source"], "bridge") for call in calls(tree, "mutate_bridge"))
    if len(calls(tree, "mutate_repository_case")) != 1 or len(calls(tree, "mutate_bridge")) != 1:
        raise ValueError("expected exactly one repository and one bridge mutation")
    targets.extend(helper_targets(tree, names, suites))
    targets.extend(design_targets(root))
    return targets


def design_targets(root: Path) -> list[Target]:
    runner = root / DESIGN_RUNNER
    if not runner.is_file() or runner.is_symlink():
        raise ValueError(f"design runner is not a regular file: {runner}")
    tree = ast.parse(runner.read_text(encoding="utf-8"), filename=str(runner))
    names = assignment_map(tree, root)
    mutations = next(
        (node.value for node in tree.body
         if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name) and node.targets[0].id == "MUTATIONS"),
        None,
    )
    if not isinstance(mutations, (ast.List, ast.Tuple)):
        raise ValueError("design MUTATIONS must be a literal sequence")
    result = []
    for index, element in enumerate(mutations.elts, start=1):
        if not isinstance(element, ast.Tuple) or len(element.elts) < 4:
            raise ValueError(f"design mutation {index} must start with name, source, before and after")
        name = literal(element.elts[0], f"design mutation {index} name")
        source = expression(element.elts[1], names, root, name)
        result.append(Target(name, source, literal(element.elts[2], f"{name} before"), literal(element.elts[3], f"{name} after"), "design"))
    return result


def validate(targets: list[Target], root: Path) -> None:
    for target in targets:
        source = target.source.resolve()
        try:
            source.relative_to(root.resolve())
        except ValueError as error:
            raise ValueError(f"{target.name} source escapes repository: {target.source}") from error
        if not source.is_file() or source.is_symlink():
            raise ValueError(f"{target.name} source is not a regular file: {source}")
        text = source.read_text(encoding="utf-8")
        if not target.after:
            raise ValueError(f"{target.name} replacement is empty; an empty string always occurs, so give a removal a comment instead")
        occurrences = text.count(target.before)
        if occurrences != 1:
            raise ValueError(f"{target.name} needle must occur exactly once in {source}: found {occurrences}")
        if target.after in text:
            raise ValueError(f"{target.name} replacement is already present in correct source: {source}")


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        raise SystemExit(f"usage: {Path(argv[0]).name} <repository-root>")
    root = Path(argv[1]).resolve(strict=True)
    targets = discover(root)
    validate(targets, root)
    kinds: dict[str, int] = {}
    for target in targets:
        kinds[target.kind] = kinds.get(target.kind, 0) + 1
    print("ok " + " ".join(f"{name}={kinds[name]}" for name in sorted(kinds)) + f" checks={len(targets)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
