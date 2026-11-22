"""`qod healthcheck manager|worker`: the container healthchecks of the one image (no curl in it).

Distinct from `qod health`, the REST call to the manager's /health through the configured profile:
these read no profile and exit 1 with the reason when unhealthy."""

import os
from pathlib import Path

import httpx
import typer

from .. import launcher
from ..fleet_join import worker_health

app = typer.Typer(help="Container healthchecks: exit 0 when healthy, 1 with the reason otherwise.")


# Not a REST call of the admin API (it reads the public /ready probe), so no @covers.
@app.command()
def manager(
    url: str = typer.Option(None, "--url", help="Readiness URL; default: http://127.0.0.1:$QOD_ON_DEMAND_PORT/ready (20900)."),
    timeout: float = typer.Option(4.0, "--timeout", help="Seconds to wait for the answer; keep it under the healthcheck's timeout."),
):
    """Healthy when the manager's /ready answers 200."""
    target = url or f"http://127.0.0.1:{os.environ.get('QOD_ON_DEMAND_PORT', '20900')}/ready"
    try:
        r = httpx.get(target, timeout=timeout, trust_env=False)
    except Exception as exc:
        typer.echo(f"unhealthy: {target}: {exc}", err=True)
        raise typer.Exit(1)
    if r.status_code != 200:
        typer.echo(f"unhealthy: {target} answered {r.status_code}", err=True)
        raise typer.Exit(1)


@app.command()
def worker(
    state_dir: Path = typer.Option(None, "--state-dir", help="The `qod fleet join --state-dir`; default: ~/.qod-fleet when it exists (the image's), else the qod cache's."),
):
    """Healthy when `qod fleet join` runs and its last heartbeat was accepted recently."""
    if state_dir is None:
        image_dir = Path.home() / ".qod-fleet"
        state_dir = image_dir if image_dir.is_dir() else launcher.default_cache_dir() / "agent"
    reason = worker_health(state_dir)
    if reason is not None:
        typer.echo(f"unhealthy: {reason}", err=True)
        raise typer.Exit(1)
