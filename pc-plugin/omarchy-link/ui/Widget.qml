import QtQuick
import Quickshell
import qs.Commons
import qs.Commons as Commons
import qs.Ui

// OmarchyTV in the Omarchy bar: TV status at a glance, a remote in the popup,
// and pairing when no TV is linked yet. All work happens in LinkSession.
BarWidget {
  id: root
  moduleName: "com.omarchytv.link"

  readonly property var session: LinkSession
  readonly property var st: LinkSession.state
  readonly property bool paired: !!st.paired
  readonly property bool connected: !!st.connected
  readonly property bool sleeping: !!st.screensaver
  readonly property string tvName: st.tv && st.tv.name ? String(st.tv.name) : "OmarchyTV"
  readonly property string appLabel: st.foreground && st.foreground.label ? String(st.foreground.label)
    : (st.homeVisible ? "Home" : "")
  readonly property bool showLabel: Boolean(setting("showLabel", true))
  readonly property int dockApps: Number(setting("appCount", 8))

  readonly property color foreground: bar ? bar.foreground : Commons.Color.foreground
  readonly property color dim: Qt.rgba(foreground.r, foreground.g, foreground.b, 0.55)
  readonly property color accent: bar ? bar.urgent : Commons.Color.accent
  readonly property string fontFamily: bar ? bar.fontFamily : "JetBrainsMono Nerd Font"

  property bool popupOpen: false
  readonly property bool opened: popupOpen
  property string viewMode: "remote"          // remote | apps | pair
  property int deviceIndex: 0
  property var pairTarget: null               // {address, port, name}

  readonly property string statusText: !paired ? "NOT PAIRED"
    : !connected ? "OFFLINE"
    : sleeping ? "SCREENSAVER"
    : (appLabel ? appLabel.toUpperCase() : "ON")

  function open() { popupOpen = true }
  function close() { popupOpen = false }

  onPopupOpenChanged: {
    if (!popupOpen) return
    viewMode = paired ? "remote" : "pair"
    if (paired) session.refresh()
    else session.discover()
    Qt.callLater(function() { keyCatcher.forceActiveFocus() })
  }
  onPairedChanged: if (popupOpen) viewMode = paired ? "remote" : "pair"

  function startPair(device) {
    pairTarget = device
    codeInput.text = ""
    Qt.callLater(function() { codeInput.forceActiveFocus() })
  }
  function submitPair() {
    var target = pairTarget
    if (!target && addressInput.text.length > 0) {
      var parts = addressInput.text.trim().split(":")
      target = { address: parts[0], port: parts.length > 1 ? Number(parts[1]) : 47800 }
    }
    var code = codeInput.text.replace(/\s/g, "")
    if (!target || code.length !== 6) return
    session.pair(target.address, target.port, code)
  }

  function handleTextKey(text) {
    var k = String(text || "").toLowerCase()
    if (viewMode === "pair") {
      if (k === "r") session.discover()
      else if (k === "q") close()
      return
    }
    if (k === "g" || k === "h") session.key("home")
    else if (k === "b") session.key("back")
    else if (k === "p" || k === " ") session.key("play_pause")
    else if (k === "+" || k === "=") session.key("volume_up")
    else if (k === "-" || k === "_") session.key("volume_down")
    else if (k === "m") session.key("mute")
    else if (k === "a") viewMode = viewMode === "apps" ? "remote" : "apps"
    else if (k === "t") session.syncTheme()
    else if (k === "c") session.castClipboard()
    else if (k === "q") close()
  }

  // #81: BarIconButton is an icon slot (fixedWidth = Style.bar.iconSlot, 27 by default) and paints its text centred
  // in that slot, so a label like the app on screen was drawn past the slot and over the next widget. The widget
  // reported `button.implicitWidth`, which is the slot and says nothing about the label. Measure the label here,
  // with the font and size the button draws with, and report the width it needs. A vertical bar has no room for a
  // horizontal label, so the label is only shown on a horizontal one, as in the shell's ActiveWindow widget.
  readonly property string buttonText: root.vertical ? "󰔂"
    : root.showLabel && root.connected && !root.sleeping && root.appLabel ? "󰔂 " + root.appLabel
    : root.sleeping ? "󰔂 󰒲" : "󰔂"
  // The side margin the button leaves around its own label, so the text does not touch the neighbouring widget.
  readonly property real labelMargin: button.scaledHorizontalMargin

  implicitWidth: root.vertical ? root.barSize
    : Math.max(root.barSize, Math.ceil(textMetrics.implicitWidth + 2 * root.labelMargin))
  implicitHeight: root.barSize

  Text {
    id: textMetrics
    visible: false
    text: root.buttonText
    font.family: root.fontFamily
    font.pixelSize: button.fontSize
  }

  BarIconButton {
    id: button
    anchors.fill: parent
    bar: root.bar
    text: root.buttonText
    opacity: root.connected ? 1 : 0.55
    active: root.popupOpen
    tooltipText: root.tvName + " · " + root.statusText.toLowerCase()
    onPressed: root.popupOpen = !root.popupOpen
  }

  component Key: Button {
    property string key: ""
    property real keyWidth: 92
    width: keyWidth
    height: 42
    enabled: root.connected && (key === "" || root.session.can("key:" + key))
    opacity: enabled ? 1 : 0.4
    foreground: root.foreground
    accent: root.accent
    fontFamily: root.fontFamily
    fontSize: Style.font.bodySmall
    iconSize: Style.font.iconLarge
    bordered: true
    onClicked: if (key) root.session.key(key)
  }

  KeyboardPanel {
    id: panel
    anchorItem: button
    owner: root
    bar: root.bar
    open: root.popupOpen
    focusTarget: keyCatcher
    contentWidth: panel.fittedContentWidth(Style.space(316))
    contentHeight: panel.fittedContentHeight(content.implicitHeight)

    PanelKeyCatcher {
      id: keyCatcher
      anchors.fill: parent
      blocked: codeInput.activeFocus || addressInput.activeFocus

      onMoveRequested: function(dx, dy) {
        if (root.viewMode === "pair") {
          var n = (root.st.devices || []).length
          if (n > 0) root.deviceIndex = Math.max(0, Math.min(n - 1, root.deviceIndex + dy))
        } else if (dx < 0) root.session.key("volume_down")
        else if (dx > 0) root.session.key("volume_up")
      }
      onActivateRequested: {
        if (root.viewMode === "pair") {
          var d = (root.st.devices || [])[root.deviceIndex]
          if (d) root.startPair(d)
        } else root.session.key("play_pause")
      }
      onCloseRequested: {
        if (root.viewMode === "apps") root.viewMode = "remote"
        else root.close()
      }
      onTextKey: function(text) { root.handleTextKey(text) }

      Column {
        id: content
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        spacing: Style.space(12)

        // ---------------------------------------------------------- header
        Item {
          width: parent.width
          height: Math.max(title.implicitHeight, badge.implicitHeight)

          Column {
            id: title
            anchors.left: parent.left
            anchors.verticalCenter: parent.verticalCenter
            spacing: Style.space(1)
            Text {
              text: root.tvName.toUpperCase()
              color: root.foreground
              font.family: root.fontFamily
              font.pixelSize: Style.font.subtitle
              font.bold: true
            }
            Text {
              text: root.viewMode === "pair" ? "OMARCHY LINK · PAIR" : "OMARCHY LINK"
              color: root.dim
              font.family: root.fontFamily
              font.pixelSize: Style.font.caption
            }
          }
          Text {
            id: badge
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            text: (root.connected ? "● " : "○ ") + root.statusText
            color: root.connected ? root.foreground : root.dim
            font.family: root.fontFamily
            font.pixelSize: Style.font.caption
            font.bold: true
          }
        }

        PanelSeparator { width: parent.width; foreground: root.foreground }

        // ---------------------------------------------------------- remote
        Column {
          visible: root.viewMode === "remote"
          width: parent.width
          spacing: Style.space(8)

          Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: Style.space(7)
            Key { key: "back"; iconText: "󰁍"; text: "BACK" }
            Key { key: "home"; iconText: "󰋜"; text: "HOME" }
            Key { key: "play_pause"; iconText: "󰐎"; text: "PLAY" }
          }
          Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: Style.space(7)
            Key { key: "volume_down"; iconText: "󰕿"; text: "VOL−" }
            Key { key: "mute"; iconText: "󰝟"; text: "MUTE" }
            Key { key: "volume_up"; iconText: "󰕾"; text: "VOL+" }
          }
          Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: Style.space(7)
            Key { iconText: "󰀻"; text: "APPS"; keyWidth: 142; onClicked: root.viewMode = "apps" }
            Key { iconText: "󰸌"; text: "SYNC THEME"; keyWidth: 142; onClicked: root.session.syncTheme() }
          }
          Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: Style.space(7)
            // Share to TV: the copied link / file path / text, via Google Cast or OmarchyTV
            Key { iconText: "󰄘"; text: "SEND CLIPBOARD"; keyWidth: 191; enabled: true; onClicked: root.session.castClipboard() }
            Key { iconText: "󰓛"; text: "STOP"; keyWidth: 92; enabled: true; onClicked: root.session.castStop() }
          }
          Text {
            width: parent.width
            visible: !!root.st.lastSync
            text: "Theme on TV: " + String(root.st.lastSync || "").replace(/-/g, " ")
            color: root.dim
            horizontalAlignment: Text.AlignHCenter
            font.family: root.fontFamily
            font.pixelSize: Style.font.caption
          }
        }

        // ---------------------------------------------------------- apps
        Column {
          visible: root.viewMode === "apps"
          width: parent.width
          spacing: Style.space(6)
          Repeater {
            model: (root.st.apps || []).slice(0, root.dockApps)
            Button {
              required property var modelData
              width: parent.width
              height: 34
              text: String(modelData.label || modelData.package).toUpperCase()
              iconText: "󰐊"
              leftAlign: true
              selected: root.st.foreground && root.st.foreground.package === modelData.package
              foreground: root.foreground
              accent: root.accent
              fontFamily: root.fontFamily
              fontSize: Style.font.bodySmall
              bordered: true
              onClicked: { root.session.launch(modelData.package); root.viewMode = "remote" }
            }
          }
        }

        // ---------------------------------------------------------- pair
        Column {
          visible: root.viewMode === "pair"
          width: parent.width
          spacing: Style.space(8)

          Text {
            width: parent.width
            text: "On the TV open the Omarchy logo menu → Connect Omarchy Linux, then pick it here and type the 6-digit code."
            wrapMode: Text.Wrap
            color: root.dim
            font.family: root.fontFamily
            font.pixelSize: Style.font.caption
          }
          Repeater {
            model: root.st.devices || []
            Button {
              required property int index
              required property var modelData
              width: parent.width
              height: 36
              text: String(modelData.name).toUpperCase() + "  ·  " + modelData.address
              iconText: "󰔂"
              leftAlign: true
              hasCursor: index === root.deviceIndex
              selected: root.pairTarget && root.pairTarget.address === modelData.address
              foreground: root.foreground
              accent: root.accent
              fontFamily: root.fontFamily
              fontSize: Style.font.bodySmall
              bordered: true
              onClicked: root.startPair(modelData)
            }
          }
          TextField {
            id: addressInput
            width: parent.width
            visible: !root.pairTarget
            placeholderText: "or TV IP, e.g. 192.168.1.50"
            foreground: root.foreground
            accent: root.accent
            onAccepted: codeInput.forceActiveFocus()
          }
          TextField {
            id: codeInput
            anchors.horizontalCenter: parent.horizontalCenter
            width: 180
            placeholderText: "000000"
            maximumLength: 7
            horizontalAlignment: TextInput.AlignHCenter
            inputMethodHints: Qt.ImhDigitsOnly
            validator: RegularExpressionValidator { regularExpression: /[0-9 ]{0,7}/ }
            foreground: root.foreground
            accent: root.accent
            onAccepted: root.submitPair()
            Keys.onEscapePressed: function(event) { keyCatcher.forceActiveFocus(); event.accepted = true }
          }
          Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: Style.space(7)
            Button {
              width: 142; height: 38
              text: root.st.discovering ? "SCANNING" : "SCAN"
              iconText: "󰑓"
              iconSpinning: !!root.st.discovering
              foreground: root.foreground; accent: root.accent
              fontFamily: root.fontFamily; fontSize: Style.font.bodySmall; bordered: true
              onClicked: root.session.discover()
            }
            Button {
              width: 142; height: 38
              text: root.st.pairing ? "PAIRING" : "PAIR"
              iconText: "󰌆"
              enabled: codeInput.text.replace(/\s/g, "").length === 6 && (!!root.pairTarget || addressInput.text.length > 0)
              opacity: enabled ? 1 : 0.5
              foreground: root.foreground; accent: root.accent
              fontFamily: root.fontFamily; fontSize: Style.font.bodySmall; bordered: true
              onClicked: root.submitPair()
            }
          }
        }

        Text {
          width: parent.width
          visible: String(root.st.error || "") !== ""
          text: String(root.st.error || "")
          wrapMode: Text.Wrap
          color: root.accent
          horizontalAlignment: Text.AlignHCenter
          font.family: root.fontFamily
          font.pixelSize: Style.font.caption
        }

        Text {
          anchors.horizontalCenter: parent.horizontalCenter
          text: root.viewMode === "pair" ? "[J/K] SELECT  [ENTER] CODE  [R] RESCAN  [ESC] CLOSE"
            : "[G] HOME [B] BACK [P] PLAY [−/+] VOL [A] APPS [T] THEME [C] CAST"
          color: root.dim
          font.family: root.fontFamily
          font.pixelSize: Style.font.caption
        }

        Button {
          visible: root.paired && root.viewMode === "remote"
          anchors.horizontalCenter: parent.horizontalCenter
          width: 291; height: 30
          text: "FORGET TV"
          iconText: "󰅖"
          foreground: root.dim; accent: root.accent
          fontFamily: root.fontFamily; fontSize: Style.font.caption; bordered: false
          onClicked: root.session.unpair()
        }
      }
    }
  }
}
