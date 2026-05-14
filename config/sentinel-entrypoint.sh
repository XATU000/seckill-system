#!/bin/sh
MASTER_IP=$(getent hosts redis-master | awk '{print $1}')
if [ -z "$MASTER_IP" ]; then
  echo "ERROR: cannot resolve redis-master"
  exit 1
fi

cat > /tmp/sentinel.conf <<EOF
port 26379
sentinel monitor seckill-master $MASTER_IP 6379 2
sentinel down-after-milliseconds seckill-master 5000
sentinel failover-timeout seckill-master 10000
sentinel parallel-syncs seckill-master 1
EOF

exec redis-sentinel /tmp/sentinel.conf
