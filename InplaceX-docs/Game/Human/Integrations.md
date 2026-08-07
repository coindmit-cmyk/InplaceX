# Integrations

## First-Class Integrations

- Ads
- Analytics
- Profile
- Social
- Online
- Google Play auth
- Billing

## First Stage Policy

- interfaces and extension points are designed first
- full backend implementation is not required in the first cycle
- online remains contract-first for now: session, matchmaking stub, transport boundary
- auth is local-first with optional sign-in
- ads and billing use stub services until the real SDK stage

## Why We Need This Now

So the local game does not have to be rewritten when we later add:

- account sync
- Google Play login
- rewarded ads
- prepaid timed access and one-time purchases
- server-backed profile and progression
