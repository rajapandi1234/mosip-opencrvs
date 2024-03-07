#!/bin/sh
# Install opencrvs-mediator
## Usage: ./install.sh [kubeconfig]

if [ $# -ge 1 ] ; then
  export KUBECONFIG=$1
fi

NS=opencrvs
CHART_VERSION=12.0.2

echo Create $NS namespace
kubectl create ns $NS

echo Istio Injection Enabled
kubectl label ns $NS istio-injection=enabled --overwrite
helm repo add mosip https://mosip.github.io/mosip-helm
helm repo update

mosip_opencrvs_partner_client_id="opencrvs-partner"
mosip_opencrvs_partner_client_secret="qYkjsUASf3cwakJOSXvYL38U8o3pGU6g"
mosip_opencrvs_partner_client_sha_secret="b8247112-f810-4848-a854-ce0699ee16b9"
mosip_opencrvs_uin_token_partner="opencrvs-auth-partner"

opencrvs_client_id="89a383a4-57fe-4392-8247-97e05441741b"
opencrvs_client_secret_key="8b5ad79e-a33a-4bad-810e-3865f886e9d6"
opencrvs_client_sha_secret="b8247112-f810-4848-a854-ce0699ee16b9"

echo Copy Configmaps.
./copy_cm.sh

echo Copy Secrets.
./copy_secrets.sh

kubectl create secret generic mosip-client-creds \
  --namespace=$NS \
  --from-literal=mosip_opencrvs_partner_client_id="$mosip_opencrvs_partner_client_id" \
  --from-literal=mosip_opencrvs_partner_client_secret="$mosip_opencrvs_partner_client_secret" \
  --from-literal=mosip_opencrvs_partner_client_sha_secret="$mosip_opencrvs_partner_client_sha_secret" \
  --from-literal=mosip_opencrvs_uin_token_partner="$mosip_opencrvs_uin_token_partner"

read -p "Please provide client private key file : " MOSIP_PRIV_KEY
  if [ -z "$MOSIP_PRIV_KEY" ]; then
    echo "MOSIP Private key file not provided; EXITING;";
    exit 0;
  fi
  if [ ! -f "$MOSIP_PRIV_KEY" ]; then
    echo "MOSIP Private key not found; EXITING;";
    exit 0;
  fi
read -p "Please provide opencrvs pub key file : " OPENCRVS_PUB_KEY

  if [ -z "$OPENCRVS_PUB_KEY" ]; then
    echo "Opencrvs public key file not provided; EXITING;";
    exit 0;
  fi
  if [ ! -f "$OPENCRVS_PUB_KEY" ]; then
    echo "Opencrvs Public key not found; EXITING;";
    exit 0;
  fi

cat "$MOSIP_PRIV_KEY" | sed "s/'//g" | sed -z 's/\n/\\n/g' > /tmp/mosip-priv.key
cat "$OPENCRVS_PUB_KEY" | sed "s/'//g" | sed -z 's/\n/\\n/g' > /tmp/opencrvs-pub.key

kubectl -n $NS create secret generic opencrvs-certs \
  --from-file="/tmp/mosip-priv.key" \
  --from-file="/tmp/opencrvs-pub.key"

kubectl create secret generic opencrvs-client-creds \
  --namespace=$NS \
  --from-literal=opencrvs_client_id="$opencrvs_client_id" \
  --from-literal=opencrvs_client_secret_key="$opencrvs_client_secret_key" \
  --from-literal=opencrvs_client_sha_secret="$opencrvs_client_sha_secret"

echo Installing mosip-side opencrvs-mediator...
helm -n $NS install opencrvs-mediator mosip/opencrvs-mediator \
  --version $CHART_VERSION \
  -f values.yaml \
  --wait

echo Installing regproc-opencrvs-stage...
helm -n $NS install regproc-opencrvs-stage mosip/regproc-opencrvs \
  --version $CHART_VERSION \
  --wait
