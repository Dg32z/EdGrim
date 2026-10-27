/**
 * Detection only: concrete checks, the processors that route packets to them, and the sub-detections those
 * processors drive.
 *
 * <p>A type belongs here when looking at it answers "what does this anticheat detect?". That covers three
 * shapes: a registered check carrying {@link cc.watchneko.checks.CheckData}, an entry processor such as
 * {@code PacketOrderProcessor} that owns a family of sub-checks, and a sub-detection that only exists to be
 * driven by one of those entry points.
 *
 * <p>Everything else lives elsewhere, even when only checks use it: helpers and data holders go to
 * {@code cc.watchneko.utils}, feature extraction and verdict policy to {@code cc.watchneko.analysis}, and
 * anything that talks to the cloud to {@code cc.watchneko.cloud}. Utility classes in particular have no
 * business here — a class of static helpers in this package is a sign the helper was filed next to its
 * first caller instead of where it belongs.
 */
package cc.watchneko.checks.impl;
