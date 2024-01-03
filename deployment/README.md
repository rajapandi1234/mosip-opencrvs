# Deployment

## Overview
This document describes deployment of `mosip-side-mediator` and `registration-processor-opencrvs-stage`.

## Prerequisites
The following command line utilities.
  - `psql`, `kubectl`,`helm`,`bash`, `curl`, `jq`

## Installation
- Set up `mosip_opencrvs` db:
  - Navigate to [db_scripts/mosip_opencrvs](../db_scripts/mosip_opencrvs).
  - Configure deploy.properties, with required secrets and hostname.
  - Then run:
    ```sh
    ./deploy.sh deploy.properties
    ```
- Create a new transaction type, 'OPENCRVS_NEW,' in the 'mosip_regprc/transaction_type' database table:
```
  INSERT INTO regprc.transaction_type(
  code, descr, lang_code, is_active, cr_by, cr_dtimes, upd_by, upd_dtimes, is_deleted, del_dtimes)
  VALUES
  ('OPENCRVS_NEW', 'OPENCRVS_NEW', 'eng', true, 'MOSIP_SYSTEM', CURRENT_TIMESTAMP, 'some_upd_by_value', CURRENT_TIMESTAMP, false, DEFAULT);
```
- Sync the OpenCRVS masterdata with MOSIP. (WIP. For now add some _states_ and _districts_ from OpenCRVS to MOSIP masterdata manually).
- Onboard a new credential type partner (using [partner onboarding scripts](https://github.com/mosip/mosip-onboarding/tree/master), or via MOSIP PMP UI), with;
  - name like `opencrvs-partner`
  - generated certificates for MOSIP OpenCRVS Mediator
  - an _auth_policy_ that has only `UIN` in _sharableAttributes_.
  - _credential_type_ is `opencrvs`.
- After partner create, make sure that credential policy and partner are mapped with the _credential_type_ `opencrvs`. Use this API to map:
  ```
  POST /v1/partnermanager/partners/{partnerId}/credentialType/opencrvs/policies/{policyName}
  ```
- Furthermore, change the following settings for the `opencrvs-partner` client (that was just created), in keycloak admin console, in `Mosip` realm.
  - Change _Access Type_ to `confidential`.
  - Enable _Standard Flow Enabled_.
  - Enable _Direct Access Grants Enabled_.
  - Enable _Service Accounts Enabled_.
  - Disable rest of all properties.
  - Change _Valid Redirect URIs_ to `*`.
- Give the following roles to this client, under _Service Account Roles_ section:
  - `SUBSCRIBE_CREDENTIAL_ISSUED_INDIVIDUAL`
  - `PUBLISH_CREDENTIAL_STATUS_UPDATE_GENERAL`
- Apart from creating the partner keycloak client, create a new user with the same username as the partner name (that was previously given), with any password.

- Run the `keycloak-init.sh` script to create a client and user, as described above
   ```
   ./keycloak-init.sh <cluster-kubeconfig-file>
   ```
- Get certificate from OpenCRVS.
- Run the following to install the mediator and components (The script will prompt for inputs):
    ```
    ./install.sh <cluster-kubeconfig-file>
    ```
  - OR Pass the following environment variables to the above script, if it is not desired to prompt for inputs:
    ```
    export OPENCRVS_AUTH_URL=
    export OPENCRVS_LOCATIONS_URL=
    export OPENCRVS_RECEIVE_CREDENTIAL_URL=
    export OPENCRVS_CLIENT_ID=
    export OPENCRVS_CLIENT_SECRET=
    export OPENCRVS_CLIENT_SHA_SECRET=
    export MOSIP_OPENCRVS_PARTNER_CLIENT_ID=
    export MOSIP_OPENCRVS_PARTNER_CLIENT_SECRET=
    export MOSIP_OPENCRVS_PARTNER_CLIENT_SHA_SECRET=
    export MOSIP_PRIVATE_KEY_PATH=
    export OPENCRVS_PUBLIC_KEY_PATH=
    ./install.sh <cluster-kubeconfig-file>
    ```
- Share the details with OpenCRVS: auth_url(mosip keycloak url), partner_client_id, partner_client_secret, partner_username, partner_password.
- Share MOSIP OpenCRVS Mediator public Certificate (that was created above).

## Uploading sample birth data
- Run the following in current directory, to upload sample birth data to mediator:
  ```sh
  curl -XPOST \
    -H "content-type: application/json" \
    -d @samples/sampleDataFromOpencrvs2.json \
    https://<opencrvs-hostname-for-mosip-mediator>/<mosip-mediator-webhooks-uri>
  ```
- Replace url with `http://localhost:4545/webhooks` if running locally.

## Uninstallation
- Run:
    ```sh
    ./delete.sh <cluster-kubeconfig-file>
    ```
