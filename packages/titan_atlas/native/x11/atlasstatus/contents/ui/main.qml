import QtQuick
import QtQuick.Layouts
import org.kde.plasma.plasmoid
import org.kde.plasma.plasma5support as Plasma5Support

PlasmoidItem {
    id: root
    // This panel shows the applet root, the same way Show Desktop does.
    preferredRepresentation: fullRepresentation

    readonly property int chipWidth: 640

    Layout.minimumWidth: chipWidth
    Layout.preferredWidth: chipWidth
    Layout.maximumWidth: chipWidth
    Layout.minimumHeight: 28
    Layout.preferredHeight: 32
    Layout.maximumHeight: 40

    property string raw: ""
    property int bars: 0
    property string caption: "phone"
    property string detail: ""

    toolTipMainText: caption
    toolTipSubText: detail.length > 0 ? detail : "LTE signal, the network carrying data, and the battery"

    function applyLine(line) {
        line = (line || "").replace(/\n/g, " ").trim()
        if (line.length === 0)
            return
        raw = line
        var p = line.split("|")
        if (p.length < 7) {
            caption = line
            bars = 0
            detail = ""
            return
        }
        var n = parseInt(p[1], 10)
        bars = isNaN(n) ? 0 : Math.max(0, Math.min(4, n))
        var rat = p[0]
        var dbm = p[2]
        var op = p[3]
        var net = p[4]
        var bat = p[5]
        var chg = p[6]
        detail = p.length > 7 ? p[7] : ""
        var text = ""
        if (rat !== "no cell" && dbm.length > 0)
            text = rat + " " + dbm + " dBm"
        else
            text = rat
        if (op.length > 0)
            text += "  " + op
        if (net.length > 0)
            text += "   " + net
        text += "   " + bat + "%" + chg
        caption = text
    }

    Rectangle {
        anchors.fill: parent
        anchors.margins: 3
        radius: 4
        color: "#20242a"
        border.color: "#d8d8d8"
        border.width: 1

        Row {
            anchors.verticalCenter: parent.verticalCenter
            anchors.left: parent.left
            anchors.leftMargin: 8
            spacing: 8

            Row {
                spacing: 2
                Repeater {
                    model: 4
                    delegate: Item {
                        width: 5
                        height: 16
                        Rectangle {
                            width: 5
                            height: 6 + index * 3
                            radius: 1
                            anchors.bottom: parent.bottom
                            color: "#f2f2f2"
                            opacity: index < root.bars ? 1 : 0.3
                        }
                    }
                }
            }

            Text {
                id: label
                text: root.caption
                color: "#f4f4f4"
                font.pixelSize: 15
                font.family: "sans-serif"
            }
        }
    }

    Plasma5Support.DataSource {
        id: src
        engine: "executable"
        interval: 5000
        connectedSources: ["/usr/local/bin/atlas-phone-status --once"]
        onNewData: function(sourceName, data) {
            var out = ""
            if (data && data["stdout"])
                out = "" + data["stdout"]
            root.applyLine(out)
            disconnectSource(sourceName)
        }
    }

    Timer {
        interval: 5000
        running: true
        repeat: true
        triggeredOnStart: true
        onTriggered: src.connectSource("/usr/local/bin/atlas-phone-status --once")
    }
}
