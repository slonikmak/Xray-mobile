# VPN client

Terms used to describe provider configuration and VPN connections.

## Language

**Provider**:
The external service that supplies connection settings and operates the remote endpoints used by the client.

**Subscription**:
A provider-managed collection of connection profiles retrieved through a subscription URL. It is distinct from the user's billing arrangement with the provider.
_Avoid_: Account, individual server configuration.

**Subscription URL**:
An address from which the client retrieves a subscription. It can contain a private access token.

**Connection profile**:
The settings needed to connect to one provider endpoint, including the protocol and authentication parameters. The interface may present this as a server entry.
_Avoid_: Subscription, account.

**Profile link**:
A link encoding an individual connection profile, as distinct from a URL used to download a subscription.

**VPN connection**:
An active tunnel that carries device application traffic through the selected provider endpoint.

**Local proxy connection**:
An active desktop Xray session that serves loopback SOCKS5 and HTTP proxies. Only applications explicitly configured to use those proxies send traffic through it; it is distinct from a VPN connection.

**Connection check**:
A network request used to check reachability through the selected VPN connection or local proxy connection. Engine/process state and the result of this check are separate observations.
