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

echo Copy Configmaps.
./copy_cm.sh

echo Copy Secrets.
./copy_secrets.sh

echo Installing mosip-side opencrvs-mediator...
helm -n $NS install opencrvs-mediator mosip/opencrvs-mediator \
  --version $CHART_VERSION \
  -f values.yaml \
  --wait

echo Installing regproc-opencrvs-stage...
helm -n $NS install regproc-opencrvs-stage mosip/regproc-opencrvs \
  --version $CHART_VERSION \
  --wait
