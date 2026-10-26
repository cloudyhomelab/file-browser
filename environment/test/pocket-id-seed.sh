#!/bin/sh
# Sets up Pocket ID for the test environment through its API: the test users, the app's OIDC client
# and a fresh client secret, which goes to the app as a Spring config-tree file. Safe to run again.
set -eu

api="$POCKET_ID_URL/api"
key="X-API-KEY: $POCKET_ID_API_KEY"
json="Content-Type: application/json"
secret_file=/run/oidc/spring.security.oauth2.client.registration.sso.client-secret

field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

until curl -fsS -o /dev/null "$POCKET_ID_URL/.well-known/openid-configuration"; do sleep 1; done

# <username> <email> <first name> <last name> <Pocket ID admin>
add_user() {
    curl -sS -o /dev/null -X POST "$api/users" -H "$key" -H "$json" \
        -d "{\"username\":\"$1\",\"email\":\"$2\",\"firstName\":\"$3\",\"lastName\":\"$4\",\"isAdmin\":$5,\"emailVerified\":true}"
}
add_user admin admin@example.com Ada Admin true
add_user alice alice@example.com Alice Tester false

new_client=false
if [ "$(curl -sS -o /dev/null -w '%{http_code}' "$api/oidc/clients/$CLIENT_ID" -H "$key")" = 404 ]; then
    curl -fsS -o /dev/null -X POST "$api/oidc/clients" -H "$key" -H "$json" \
        -d "{\"id\":\"$CLIENT_ID\",\"name\":\"FileBrowser\",\"callbackURLs\":[\"$CALLBACK_URL\"]}"
    new_client=true
fi

# Pocket ID makes up the secret, and the app reads it only when starting, so a running app keeps its
# secret; a new one replaces the old ones only for a new client or when the file is gone
if [ "$new_client" = true ] || [ ! -s "$secret_file" ]; then
    for id in $(curl -fsS "$api/oidc/clients/$CLIENT_ID/secrets" -H "$key" | grep -o '"id":"[^"]*"' | cut -d'"' -f4); do
        curl -fsS -o /dev/null -X DELETE "$api/oidc/clients/$CLIENT_ID/secrets/$id" -H "$key"
    done
    secret=$(curl -fsS -X POST "$api/oidc/clients/$CLIENT_ID/secrets" -H "$key" -H "$json" -d '{"expiresAt":null}' | field secret)
    printf '%s' "$secret" > "$secret_file.tmp"
    chmod 644 "$secret_file.tmp"
    mv "$secret_file.tmp" "$secret_file"
fi

echo "Sign in to http://localhost:8180 with one of these one-time links (valid for an hour):"
for email in admin@example.com alice@example.com; do
    user=$(curl -fsS "$api/users?search=$(echo "$email" | sed 's/@/%40/')" -H "$key" | grep -o '"id":"[^"]*"' | head -n 1 | cut -d'"' -f4)
    token=$(curl -fsS -X POST "$api/users/$user/one-time-access-token" -H "$key" -H "$json" -d '{"ttl":"1h"}' | field token)
    echo "  $email: $POCKET_ID_PUBLIC_URL/lc/$token"
done
