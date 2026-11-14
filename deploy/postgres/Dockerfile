FROM docker.io/library/postgres:18.6-alpine@sha256:123b17998ccaf47e53b502e8c6a1e79afd8f579c6f3eec83c563da026f957395

ARG PGVECTOR_VERSION=0.8.6
ARG PGVECTOR_COMMIT=8ee86c96f0fd72390f890aa8a336fda6d3ab4c6c
ARG PGVECTOR_SHA256=b669ffedc5ccb50a1cbd17532160df022b10af7274162d45577bb05d4e1d2dc1

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

RUN rm /usr/local/bin/gosu

RUN apk upgrade --no-cache zlib

USER postgres
