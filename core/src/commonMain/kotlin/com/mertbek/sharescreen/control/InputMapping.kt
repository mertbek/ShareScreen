package com.mertbek.sharescreen.control

import com.mertbek.sharescreen.link.ConnectLink
import com.mertbek.sharescreen.session.JoinTarget

fun mapToPicture(fit: FittedRect, zoom: Zoom, x: Float, y: Float): Pair<Float, Float> =
    zoom.pictureX(fit.fractionX(x)) to zoom.pictureY(fit.fractionY(y))

fun ConnectLink.toJoinTarget(): JoinTarget = when (this) {
    is ConnectLink.Lan -> JoinTarget.Lan(host, port)
    is ConnectLink.Internet -> JoinTarget.Internet(server, roomCode)
}
