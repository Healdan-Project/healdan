"""Loads the scripts in .github/scripts by path, since their names have hyphens."""

import importlib.util
import pathlib

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
REPO = SCRIPTS.parents[1]


def load(name):
    """Returns .github/scripts/<name>.py as a module."""
    path = SCRIPTS / f"{name}.py"
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
