# syntax=docker/dockerfile:1.7
#
# starlakeai/quack-on-demand-worker, kept for two releases after 0.14.2 so that existing fleet
# servers keep starting: the one image (starlakeai/quack-on-demand, root Dockerfile) in its worker
# role. New deployments run the one image with `command: [worker]`. No RUN step, so buildx builds
# every platform without emulation.
ARG BASE
FROM ${BASE}
LABEL org.opencontainers.image.title=quack-on-demand-worker \
      org.opencontainers.image.description="Deprecated alias: starlakeai/quack-on-demand in its worker role (command: [worker])."
CMD ["worker"]
