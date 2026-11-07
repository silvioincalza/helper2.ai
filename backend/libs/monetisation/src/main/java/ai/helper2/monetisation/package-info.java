/**
 * Monetisation (ADR 0030): donations, analytics and the one ad slot. It owns no table and makes no
 * call: it reads its {@code H2_} settings and publishes them as {@link
 * ai.helper2.monetisation.Monetisation}, which identity serves in GET /api/v1/me (nothing on the
 * admin host) and reads to build the Content Security Policy. Every part is off until its setting
 * is filled.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Monetisation")
package ai.helper2.monetisation;
