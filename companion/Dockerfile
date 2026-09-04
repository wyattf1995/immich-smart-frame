# Official Python manifest verified 2026-09-04; updates require an explicit review.
FROM python:3.13-alpine@sha256:7415fbc3c9e4979cc717d92377ab2bc7b2b4a2af1ac03cc52b5f3f88efedaf3a
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY server.py index.html ./
USER 10001:10001
EXPOSE 8092
CMD ["python", "/app/server.py"]
