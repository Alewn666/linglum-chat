FROM python:3.11-slim

WORKDIR /app
COPY requirements.txt .
RUN pip3 install --no-cache-dir -i https://mirrors.aliyun.com/pypi/simple/ -r requirements.txt

COPY app.py .

ENV CHAT_DATA_DIR=/data
EXPOSE 8097
CMD ["gunicorn", "-w", "1", "-k", "gthread", "--threads", "32", "--timeout", "60", "-b", "0.0.0.0:8097", "app:app"]
