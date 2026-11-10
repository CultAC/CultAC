FROM eclipse-temurin:25-jdk-noble

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl git python3 unzip xvfb xauth x11-utils fonts-dejavu-core \
    libgl1-mesa-dri libglx-mesa0 libxrender1 libxtst6 libxi6 libxrandr2 \
    libxcursor1 libxinerama1 libasound2t64 libgtk-3-0t64 \
    && rm -rf /var/lib/apt/lists/*
