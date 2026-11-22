FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && useradd --system --uid 10001 simulator
COPY frontline_sim frontline_sim
COPY scripts scripts
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PYTHONPATH=/app
USER simulator
CMD ["python", "-m", "frontline_sim.main"]
