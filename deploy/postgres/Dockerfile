# Postgres 18 (Alpine, like before) with pgvector for vector search (ADR 0016).
# Same base image as the running database, so the data directory and its collations stay valid: switching
# to the Debian-based pgvector/pgvector image would change the C library and need a REINDEX.
# Built and pushed by `make -C deploy/k8s postgres-image`, under postgres.image.tag of the Helm chart's
# values.yaml, and only when that tag is not in Harbor yet. Any change here needs a new tag there.
# Pinned by the digest of its multi-arch index (ADR 0046); Dependabot updates tag and digest together.
FROM docker.io/library/postgres:18.6-alpine@sha256:10a5c69a83035ab5239f5620d708520e8b28f5aa1f2b20143e6024532edadfb5

# The source is pinned twice: the tag must resolve to this commit, and the files must hash to this
# sha256, so a moved tag or a tampered download fails the build. For a new version:
#   git clone --quiet --depth 1 --branch v<version> https://github.com/pgvector/pgvector.git p
#   git -C p rev-parse HEAD; rm -rf p/.git
#   (cd p && find . -type f | LC_ALL=C sort | while IFS= read -r f; do sha256sum "$f"; done | sha256sum)
ARG PGVECTOR_VERSION=0.8.6
ARG PGVECTOR_COMMIT=8ee86c96f0fd72390f890aa8a336fda6d3ab4c6c
ARG PGVECTOR_SHA256=b669ffedc5ccb50a1cbd17532160df022b10af7274162d45577bb05d4e1d2dc1

# OPTFLAGS="": no -march=native, so the build host's CPU does not leak into the image.
# with_llvm=no: no JIT bitcode, so clang/llvm are not needed.
RUN apk add --no-cache --virtual .pgvector-build build-base git \
    && git clone --quiet --branch "v${PGVECTOR_VERSION}" --depth 1 https://github.com/pgvector/pgvector.git /tmp/pgvector \
    && commit=$(git -C /tmp/pgvector rev-parse HEAD) \
    && { [ "$commit" = "$PGVECTOR_COMMIT" ] || { echo "pgvector v${PGVECTOR_VERSION} is $commit, expected $PGVECTOR_COMMIT"; exit 1; }; } \
    && rm -rf /tmp/pgvector/.git \
    && sum=$(cd /tmp/pgvector && find . -type f | LC_ALL=C sort | while IFS= read -r file; do sha256sum "$file"; done | sha256sum | cut -d' ' -f1) \
    && { [ "$sum" = "$PGVECTOR_SHA256" ] || { echo "pgvector source sha256 is $sum, expected $PGVECTOR_SHA256"; exit 1; }; } \
    && make -C /tmp/pgvector OPTFLAGS="" with_llvm=no \
    && make -C /tmp/pgvector install with_llvm=no \
    && rm -rf /tmp/pgvector \
    && apk del .pgvector-build

# Ends as postgres (uid 70), the user the chart runs it as (runAsUser: 70), not as root.
USER postgres
