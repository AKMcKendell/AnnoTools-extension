/*
 * Author: Alec McKendell
 * Date: 2026.6.8
 * Description: Script to remove objects by class.
*/


// --- FUNCTIONS ---
// Function: Helper to run on FX thread. An error there is thrown again here, so the caller stops (rather
// than carrying on as if the code had run)
def runOnFxThreadAndWait( Closure fxCode ) {
    def latch = new CountDownLatch( 1 )
    def result = [value: null, error: null]
    Platform.runLater {
        try {
            result.value = fxCode.call()
        } catch (Throwable t) {
            result.error = t
        } finally {
            latch.countDown()
        }
    }
    latch.await()
    if (result.error != null) { throw result.error }
    return result.value
}

// Function: Assign or update tooltips
def setTT( objs, String tooltip ) {
    objs.each { obj ->
        if (obj instanceof Control) {
            obj.getTooltip() ? obj.getTooltip().setText( tooltip ) : obj.setTooltip( new Tooltip( tooltip ) )
        } else {
            def currentTT = obj.getProperties().get( "tooltip" )
            if (currentTT) {
                currentTT.setText( tooltip )
            } else {
                def newTT = new Tooltip( tooltip )
                Tooltip.install( obj, newTT )
                obj.getProperties().put( "tooltip", newTT )
            }
        }
    }
}

// Function: Widest a window may be — the width of the screen it's on (or, before it's shown, its
// owner's screen), less a small margin
def maxWindowWidth( Window window ) {
    def ref = window?.isShowing() ? window : (window instanceof Stage ? window.getOwner() : null)
    def screen = ref ? Screen.getScreensForRectangle( ref.getX(), ref.getY(), ref.getWidth(), ref.getHeight() ).find() : null
    return (screen ?: Screen.getPrimary()).getVisualBounds().getWidth() - 20
}

// Function: Move a window back inside its screen if it hangs off the left or right edge
def keepOnScreen( Window window ) {
    def screen = Screen.getScreensForRectangle( window.getX(), window.getY(), window.getWidth(), window.getHeight() ).find() ?: Screen.getPrimary()
    def bounds = screen.getVisualBounds()
    double x = Math.min( window.getX(), bounds.getMaxX() - window.getWidth() )
    window.setX( Math.max( x, bounds.getMinX() ) )
}

// Function: Create window showing script progress
def createProgressWindow( String title, int totalImages, List imageNames = [] ) {
    return runOnFxThreadAndWait {
        def stage = new Stage()
        stage.setMinWidth( 350 )
        stage.setResizable( false )
        stage.setTitle( title )
        
        def imageNum = new SimpleStringProperty( "0" )
        def imageName = new SimpleStringProperty( "Starting..." )
        def imageLabel = new Label()
        imageLabel.textProperty().bind(
            Bindings.concat( "Image ", imageNum, "/", totalImages, ": ", imageName )
        )      
        def imageRow = new HBox( 5, imageLabel )
        
        def bar = new ProgressBar( 0.0 )
        bar.setPrefWidth( 350 )
        bar.setMaxWidth( Double.MAX_VALUE )
        HBox.setHgrow( bar, Priority.ALWAYS )
        def barRow = new HBox( 10, bar )
        
        def stopBtn = new Button( "Stop" )
        stopBtn.setDisable( false )
        def spacer = new Region()
        HBox.setHgrow( spacer, Priority.ALWAYS )
        def timerLabel = new Label( "Elapsed: 00:00:00" )       
        def bottomRow = new HBox( 10, stopBtn, spacer, timerLabel )
        bottomRow.setAlignment( Pos.CENTER_LEFT )
        
        List rows = [imageRow, barRow, bottomRow]
        
        def vbox = new VBox( 10 )
        vbox.getChildren().addAll( rows )
        vbox.setPadding( new Insets( 10 ) )
        vbox.setAlignment( Pos.CENTER_LEFT )
        
        def updateMinWidth = {
            double preferred = rows.collect { it.getWidth() }.max() + vbox.getPadding().getLeft() + vbox.getPadding().getRight()
            if ( preferred > stage.getWidth() ) {
                stage.setWidth( Math.min( preferred, maxWindowWidth( stage ) ) )
            }
        }
        rows.each { node ->
            node.setMinWidth( Region.USE_PREF_SIZE )
            node.widthProperty().addListener { obs, old, nw -> updateMinWidth() }
        }
        
        def updateStageWidth = {
            Platform.runLater {
                double maxRowPref = rows.collect { it.prefWidth(-1) }.max()
                double needed = Math.max(maxRowPref + vbox.getPadding().getLeft() + vbox.getPadding().getRight(), 350)
                if (needed > stage.getWidth()) {
                    stage.setWidth( Math.min( needed, maxWindowWidth( stage ) ) )
                }
            }
        }
        [imageNum, imageName].each { prop ->
            prop.addListener { obs, old, nw -> updateStageWidth() }
        }
        
        def scene = new Scene( vbox )
        stage.setScene( scene )
        
        def gui = QuPathGUI.getInstance()
        if (gui != null) {
            stage.initOwner( gui.getStage() )
        }
        stage.initModality( Modality.WINDOW_MODAL )
        stage.show()

        // Never wider than the screen: any text line too long to fit (e.g. a long image name) is
        // shortened in the middle instead, keeping its start and end
        double maxContentWidth = maxWindowWidth( stage ) - vbox.getPadding().getLeft() - vbox.getPadding().getRight()
        rows.each { row ->
            row.getChildren().findAll { it instanceof Label }.each { label ->
                label.setMaxWidth( maxContentWidth )
                label.setTextOverrun( OverrunStyle.CENTER_ELLIPSIS )
            }
        }
        // The window grows rightwards from where it opened, so pull it back if that runs it off screen
        stage.widthProperty().addListener { obs, oldVal, newVal -> keepOnScreen( stage ) }

        // Pre-size once to fit the longest image name (plus every other row), never
        // below the minimum width, so the window opens at its final size rather than
        // growing as image names appear during the run. The grow-never-shrink logic
        // stays in place as a floor so unusually long step text still can't be clipped.
        String widestImageName = (imageNames ?: []).collect { it?.toString() ?: "" }.max { it.length() } ?: ""
        String initialImageName = imageName.get()
        String initialImageNum  = imageNum.get()
        imageName.set( widestImageName )
        imageNum.set( totalImages.toString() )
        vbox.applyCss()
        vbox.layout()
        // The stage width includes the OS window frame, so the usable scene is
        // narrower than the stage by its side borders (a few px on Windows, ~0 on
        // macOS). Add that back or the content's right edge gets clipped.
        double frameWidth = scene.getWidth() > 0 ? Math.max( stage.getWidth() - scene.getWidth(), 0 ) : 0
        double contentWidth = rows.collect { it.prefWidth( -1 ) }.max() + vbox.getPadding().getLeft() + vbox.getPadding().getRight()
        double baselineWidth = Math.max( contentWidth + frameWidth, stage.getMinWidth() )
        if (baselineWidth > stage.getMinWidth()) { stage.setMinWidth( Math.min( baselineWidth, maxWindowWidth( stage ) ) ) }
        if (baselineWidth > stage.getWidth())    { stage.setWidth( Math.min( baselineWidth, maxWindowWidth( stage ) ) ) }
        imageName.set( initialImageName )
        imageNum.set( initialImageNum )
        updateStageWidth()
        
        def startTime = System.nanoTime()
        
        def timerTimeline = new Timeline(
            new KeyFrame(
                javafx.util.Duration.seconds( 1 ),
                { evt ->
                    long elapsedNs = System.nanoTime() - startTime
                    long seconds = (long)( elapsedNs / 1_000_000_000L )
                    long hrs = seconds / 3600
                    long mins = (seconds % 3600) / 60
                    long secs = seconds % 60
                    timerLabel.setText(
                        String.format( "Elapsed: %02d:%02d:%02d", hrs, mins, secs )
                    )
                }
            )
        )
        timerTimeline.setCycleCount( Animation.INDEFINITE )
        timerTimeline.play()
        
        // Shared atomic state written by worker threads, read by poll timeline
        def doneCount = new AtomicInteger( 0 )
        def lastImageName = new AtomicReference<String>( "Starting..." )
        
        // Poll at 100 ms — faster than the render pulse, so no burst is missed
        def pollTimeline = new Timeline(
            new KeyFrame(
                Duration.millis( 100 ),
                { evt ->
                    int done = doneCount.get()
                    imageNum.set( "${done}" )
                    imageName.set( lastImageName.get() )
                    bar.setProgress( totalImages > 0 ? done / (double)totalImages : 0.0 )
                }
            )
        )
        pollTimeline.setCycleCount( Animation.INDEFINITE )
        pollTimeline.play()
        
        // Stop both timelines when the stage is actually closed
        stage.setOnHidden {
            timerTimeline.stop()
            pollTimeline.stop()
        }
        
        // Signal to stop script
        def stopLaterFlag = new AtomicBoolean( false )
        
        def stopLaterLabel = new Label( "Stopping after image..." )
        stopLaterLabel.setPadding( new Insets( 4 ) )
        
        def showStopConfirmation = {
            if (stopLaterFlag.get()) { return }
            def alert = new Alert( Alert.AlertType.CONFIRMATION )
            alert.setTitle( "Stop confirmation" )
            alert.setHeaderText( "Stop processing?" )
            alert.initOwner( stage )
            def response = alert.showAndWait().orElse( null )
            if (response == ButtonType.OK) {
                stopLaterFlag.set( true )
                bottomRow.getChildren().set( 0, stopLaterLabel )
            }
        }

        stopBtn.setOnAction { showStopConfirmation() }
        stage.setOnCloseRequest { evt ->
            evt.consume()
            showStopConfirmation()
        }
        
        return [
            stage        : stage,
            stopLaterFlag: stopLaterFlag,
            doneCount    : doneCount,
            lastImageName: lastImageName
        ]
    }
}

// Function: Lock a node's current dimensions to prevent resizing
def lockDimensions( Node nodes ) {
    Platform.runLater {
        nodes.each { node ->
            double h = node.getHeight()
            node.setMinHeight( h )
            node.setPrefHeight( h )
            node.setMaxHeight( h )
            double w = node.getWidth()
            node.setMinWidth( w )
            node.setPrefWidth( w )
            node.setMaxWidth( w )
        }
    }
}

// Function: Make a checkbox that selects/deselects all checkboxes
def makeSelectAllCB( List groupCBs ) {
    def selectAllCB = new CheckBox()
    
    // Select / deselect all action. Only the boxes on show: a hidden one keeps its state
    selectAllCB.setOnAction {
        selectAllCB.setIndeterminate( false )
        groupCBs.findAll { it.isVisible() }.each { it.setSelected( selectAllCB.isSelected() ) }
    }
    
    // Update relevant labels/instructions when boxes are clicked
    groupCBs.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal ->
            def shownCBs = groupCBs.findAll { it.isVisible() }
            int numSelected = shownCBs.count { it.isSelected() }
            
            if (numSelected !in [0, shownCBs.size()]) {
                selectAllCB.setIndeterminate( true )
            
            } else {
                selectAllCB.setIndeterminate( false )
                selectAllCB.setSelected( numSelected > 0 )
            }
        }
    } 
    
    return selectAllCB
}

// Function: Create icon for UI
def addIcon( node, iconCode, int size ) {
    def icon = IconFactory.createNode( iconCode, size )
    node.setGraphic( icon )
}

// Function: Outline an input in red for as long as it is a reason the "OK" button is disabled
def flagWhenBlocking( node, blocking, String blockedStyle = null ) {
    def outlineRedStyle = """
        -fx-border-color: red;
        -fx-border-radius: 3;
    """
    def flaggedStyle = blockedStyle ?: outlineRedStyle // e.g. an underline in place of an outline
    def clearedStyle = node.getStyle()                 // however the input looks when it isn't blocking

    // A node with no border of its own gets an invisible copy of the flag's border, so the flag
    // coming and going doesn't change the node's size and nudge the layout around it
    if (!clearedStyle?.contains( "-fx-border-color" )) {
        clearedStyle = (clearedStyle ?: "") + flaggedStyle.replace( "red", "transparent" )
    }

    // Hold the binding on the node — a derived binding (e.g. someBinding.not()) is only weakly
    // referenced by its dependencies, so otherwise it can be collected part way through the dialog
    node.getProperties().put( "flagWhenBlocking", blocking )

    def applyStyle = { boolean isBlocking -> node.setStyle( isBlocking ? flaggedStyle : clearedStyle ) }
    blocking.addListener { obs, oldVal, newVal -> applyStyle( newVal ) }
    applyStyle( blocking.getValue() )
}

// Function: While the mouse is over a disabled control, show why it's disabled. JavaFX never shows a
// disabled control's own tooltip (it isn't even picked under the mouse), so this follows the mouse from
// the window instead. reasons() gives the text: a String, or a map of each reason to whether it applies
// right now. Nothing shows while no reason applies
def explainWhenDisabled( Node node, Closure reasons ) {
    def tip = new Tooltip()
    def delay = new PauseTransition( tip.getShowDelay() ) // waits as long as an ordinary tooltip
    double mouseX = 0, mouseY = 0
    delay.setOnFinished {
        def window = node.getScene()?.getWindow()
        if (window?.isShowing()) { tip.show( window, mouseX + 10, mouseY + 7 ) } // where JavaFX puts its own
    }
    def hideTip = { delay.stop(); tip.hide() }

    def onMove = { MouseEvent e ->
        // The window picks the nearest enabled ancestor under the mouse: walk up to it through visible
        // nodes, and check the node itself is under the mouse (not a part scrolled out of view)
        def picked = e.getPickResult().getIntersectedNode()
        def n = node
        while (n != null && n != picked && n.isVisible()) { n = n.getParent() }
        boolean over = node.isDisabled() && picked != null && n == picked &&
                       node.contains( node.sceneToLocal( e.getSceneX(), e.getSceneY() ) )

        def why = over ? reasons() : null
        List lines = why instanceof Map ? why.findAll { it.value }.collect { it.key as String } : (why ? [why as String] : [])
        if (!lines) { hideTip(); return }
        tip.setText( lines.size() > 1 ? lines.collect { "• " + it }.join( "\n" ) : lines[ 0 ] )
        mouseX = e.getScreenX()
        mouseY = e.getScreenY()
        if (!tip.isShowing()) { delay.playFromStart() }
    } as javafx.event.EventHandler
    def onExit = { MouseEvent e -> hideTip() } as javafx.event.EventHandler

    // Follow the node into whichever window shows it
    def attach = { oldScene, newScene ->
        hideTip()
        oldScene?.removeEventFilter( MouseEvent.MOUSE_MOVED, onMove )
        oldScene?.removeEventHandler( MouseEvent.MOUSE_EXITED, onExit )
        newScene?.addEventFilter( MouseEvent.MOUSE_MOVED, onMove )
        newScene?.addEventHandler( MouseEvent.MOUSE_EXITED, onExit )
    }
    node.sceneProperty().addListener { obs, oldScene, newScene -> attach( oldScene, newScene ) }
    attach( null, node.getScene() )
    node.disabledProperty().addListener { obs, oldVal, isDisabled -> if (!isDisabled) { hideTip() } }
}

// Function: Add a "?" button to a dialog's button row that opens a help panel beside the dialog: a short
// intro, then an entry for each input. entries are maps of title, text and nodes (the controls the entry is
// about; oneBox: true outlines them as one block rather than row by row). While the panel is open the two
// are linked: pointing at an entry outlines its controls in the dialog, and pointing at a control picks
// out its entry. An entry can also give now: a closure returning a sentence about the dialog as it stands
// (what is chosen, what that will do), shown under its text as "Current: ..." and kept up to date while the
// panel is open. Call it once the dialog's inputs are all built
def addHelpButton( Dialog dialog, String heading, String intro, List entries ) {
    def pane = dialog.getDialogPane()
    String blue    = "rgb(47,143,230)"
    String cardOff = "-fx-background-radius: 4; -fx-border-width: 0 0 0 3; -fx-border-color: transparent;"
    String cardOn  = cardOff.replace( "transparent", blue ) + " -fx-background-color: rgba(47,143,230,0.15);"

    // A dialog's buttons close it when clicked, so this one's click is taken before the dialog sees it
    def helpType = new ButtonType( "?", ButtonBar.ButtonData.HELP_2 )
    pane.getButtonTypes().add( helpType )
    def helpBtn = pane.lookupButton( helpType )
    ButtonBar.setButtonUniformSize( helpBtn, false )
    helpBtn.setFocusTraversable( false ) // the input being typed in keeps the focus, and the tab order is unchanged
    helpBtn.setStyle( """
        -fx-font-weight: bold;
        -fx-padding: 0;
        -fx-background-radius: 5em;
        -fx-min-width: 2em;  -fx-pref-width: 2em;  -fx-max-width: 2em;
        -fx-min-height: 2em; -fx-pref-height: 2em; -fx-max-height: 2em;
    """ )
    setTT( helpBtn, "Open a dynamic help panel showing what each option does." )

    List items = entries.collect { [entry: it, card: null, nowLabel: null] } // card: the entry's block in the panel
    def current = null                                                       // the item being pointed at, if any
    Stage panel = null
    ScrollPane scroll = null
    def scrolling = new javafx.animation.Timeline()

    // The "Current: ..." lines, re-read a few times a second while the panel is open: simpler and surer than
    // listening to every input an entry might depend on. A line that can't be worked out is left off
    def refreshNow = {
        items.each { item ->
            if (item.nowLabel == null) { return }
            String now = null
            try { now = item.entry.now.call() } catch (Exception ignored) { }
            String shown = now ? "Current: " + now : ""
            if (shown != item.nowLabel.getText()) { item.nowLabel.setText( shown ) }
            item.nowLabel.setVisible( !shown.isEmpty() )
            item.nowLabel.setManaged( !shown.isEmpty() )
        }
    }
    def keepingUp = new javafx.animation.Timeline( new javafx.animation.KeyFrame( Duration.millis( 250 ), { refreshNow() } as javafx.event.EventHandler ) )
    keepingUp.setCycleCount( javafx.animation.Animation.INDEFINITE )

    // Outlines drawn over the dialog around the controls being explained. The dialog doesn't lay them out
    // and the mouse passes through them, so nothing under them moves or stops responding
    def outlines = new javafx.scene.Group()
    outlines.setManaged( false )
    outlines.setMouseTransparent( true )
    String outlineStyle = "-fx-stroke: ${blue}; -fx-stroke-width: 2; -fx-stroke-type: inside; -fx-arc-width: 8;" +
                          "-fx-arc-height: 8; -fx-fill: rgba(47,143,230,0.10);"

    // Where an entry's controls are in the dialog's scene: a rectangle around each row of those on show.
    // Controls side by side share one; controls above one another get their own, so that a group spread
    // over two rows isn't boxed together with whatever else sits beside it (unless the entry asks for
    // oneBox, e.g. a label sitting on top of its field)
    def rowsOf = { entry ->
        List shown = entry.nodes.findAll { node ->
            def n = node
            while (n != null && n.isVisible()) { n = n.getParent() }
            n == null && node.getScene() == pane.getScene() // not hidden, and in the dialog
        }.collect { it.localToScene( it.getBoundsInLocal() ) }.findAll { it.getWidth() > 0 && it.getHeight() > 0 }
        List rows = []
        shown.sort { it.getMinY() }.each { b ->
            def row = rows ? rows.last() : null
            if (row != null && (entry.oneBox || b.getMinY() < row.getMaxY())) {
                double x0 = Math.min( row.getMinX(), b.getMinX() )
                rows[ -1 ] = new BoundingBox( x0, row.getMinY(), Math.max( row.getMaxX(), b.getMaxX() ) - x0,
                                              Math.max( row.getMaxY(), b.getMaxY() ) - row.getMinY() )
            } else {
                rows << b
            }
        }
        return rows
    }

    // Point at an item (or at nothing): its card is picked out in the panel and its controls are outlined
    // in the dialog. reveal also scrolls the panel to the card, for when the pointing is done in the dialog
    def pointAt = { item, boolean reveal ->
        boolean changed = item !== current
        current = item
        if (changed) { items.each { it.card?.setStyle( it === item ? cardOn : cardOff ) } }

        List rows = (item != null && panel?.isShowing()) ? rowsOf( item.entry ) : []
        if (rows) {
            // In front of the dialog's own parts, which it adds to itself as they're set
            if (outlines.getParent() == null) { pane.getChildren().add( outlines ) }
            if (pane.getChildren().last() !== outlines) { outlines.toFront() }
        }
        while (outlines.getChildren().size() < rows.size()) {
            def shape = new javafx.scene.shape.Rectangle()
            shape.setStyle( outlineStyle )
            outlines.getChildren().add( shape )
        }
        outlines.getChildren().eachWithIndex { shape, i ->
            shape.setVisible( i < rows.size() )
            if (i >= rows.size()) { return }
            def local = pane.sceneToLocal( rows[ i ] )
            double x = Math.floor( local.getMinX() ) - 4, y = Math.floor( local.getMinY() ) - 4
            shape.setX( x )
            shape.setY( y )
            shape.setWidth( Math.ceil( local.getMaxX() ) + 4 - x )
            shape.setHeight( Math.ceil( local.getMaxY() ) + 4 - y )
        }

        if (!changed || !reveal || item == null) { return }
        double viewH = scroll.getViewportBounds().getHeight()
        double spare = scroll.getContent().getBoundsInLocal().getHeight() - viewH // how far the panel can scroll
        if (spare <= 0) { return }
        def cardAt = item.card.getBoundsInParent()
        double top = scroll.getVvalue() * spare
        double want = top
        if (cardAt.getMinY() - 8 < top)              { want = cardAt.getMinY() - 8 }
        else if (cardAt.getMaxY() + 8 > top + viewH) { want = Math.min( cardAt.getMinY() - 8, cardAt.getMaxY() + 8 - viewH ) }
        want = Math.max( 0d, Math.min( spare, want ) )
        if (Math.abs( want - top ) < 0.5) { return }
        scrolling.stop()
        scrolling.getKeyFrames().setAll( new javafx.animation.KeyFrame( Duration.millis( 150 ),
            new javafx.animation.KeyValue( scroll.vvalueProperty(), (want / spare) as double ) ) )
        scrolling.playFromStart()
    }

    // The panel is only built if it's asked for
    def buildPanel = {
        def title = new Label( heading )
        title.setStyle( "-fx-font-size: 1.3em; -fx-font-weight: bold;" )
        def about = new Label( intro )
        def howTo = new Label( "Hover over an option below to see it outlined in the window, or over something in the window to find it here." )
        howTo.setStyle( "-fx-font-style: italic; -fx-opacity: 0.7;" )
        def content = new VBox( 8, title, about, howTo, new Separator() )
        content.setPadding( new Insets( 12, 10, 12, 10 ) )

        items.each { item ->
            def name = new Label( item.entry.title as String )
            name.setStyle( "-fx-font-weight: bold;" )
            def text = new Label( item.entry.text as String )
            def card = new VBox( 3, name, text )
            card.setPadding( new Insets( 6, 8, 6, 8 ) )
            card.setStyle( cardOff )
            card.setOnMouseEntered { pointAt( item, false ) }
            card.setOnMouseExited { pointAt( null, false ) }
            item.card = card
            content.getChildren().add( card )
            [name, text].each { it.setWrapText( true ) }

            if (item.entry.now instanceof Closure) {
                item.nowLabel = new Label()
                item.nowLabel.setWrapText( true )
                item.nowLabel.setMaxWidth( Double.MAX_VALUE )
                item.nowLabel.setStyle( "-fx-background-color: rgba(47,143,230,0.13); -fx-background-radius: 4; -fx-padding: 4 6 4 6;" )
                VBox.setMargin( item.nowLabel, new Insets( 3, 0, 0, 0 ) )
                card.getChildren().add( item.nowLabel )
            }
        }
        [about, howTo].each { it.setWrapText( true ) }

        scroll = new ScrollPane( content )
        scroll.setFitToWidth( true )
        scroll.setHbarPolicy( ScrollPane.ScrollBarPolicy.NEVER )

        // Owned by the dialog, so it stays in front of it, takes input while the dialog is up and closes with it.
        // An ordinary window rather than a utility one: on a Mac a utility window gets a thin title bar whose
        // close/minimize/zoom buttons sit out of line
        def stage = new Stage()
        stage.initOwner( pane.getScene().getWindow() )
        stage.setTitle( heading + " help" )
        stage.setScene( new Scene( scroll ) )
        stage.setMinWidth( 240 )
        stage.setMinHeight( 200 )
        stage.getScene().addEventHandler( KeyEvent.KEY_PRESSED ) { KeyEvent e -> if (e.getCode() == KeyCode.ESCAPE) { stage.hide() } }
        stage.showingProperty().addListener { obs, was, showing ->
            if (showing && items.any { it.nowLabel != null }) { keepingUp.play() }
            if (!showing) { keepingUp.stop(); pointAt( null, false ) }
        }

        // Pointing at a control in the dialog: the entry whose controls are under the mouse (the smallest
        // such, if several are). Judged by position, as a disabled control is never picked under the mouse
        def dialogScene = pane.getScene()
        dialogScene.addEventFilter( MouseEvent.MOUSE_MOVED ) { MouseEvent e ->
            if (!stage.isShowing()) { return }
            def hit = null
            double hitArea = Double.MAX_VALUE
            items.each { item ->
                List rows = rowsOf( item.entry )
                double area = rows.sum { it.getWidth() * it.getHeight() } ?: 0d
                if (area < hitArea && rows.any { it.contains( e.getSceneX(), e.getSceneY() ) }) {
                    hit = item
                    hitArea = area
                }
            }
            pointAt( hit, true )
        }
        dialogScene.addEventHandler( MouseEvent.MOUSE_EXITED ) { MouseEvent e -> pointAt( null, false ) }

        return stage
    }

    helpBtn.addEventFilter( javafx.event.ActionEvent.ACTION ) { e ->
        e.consume()
        if (panel == null) { panel = buildPanel() }
        if (panel.isShowing() && !panel.isIconified()) { panel.hide(); return }
        panel.setIconified( false ) // minimized: bring it back rather than closing it unseen

        // Opens beside the dialog and as tall as it: to its right, else to its left, else (no room on
        // either side) over its right-hand edge
        def window = pane.getScene().getWindow()
        def screen = Screen.getScreensForRectangle( window.getX(), window.getY(), window.getWidth(), window.getHeight() ).find() ?: Screen.getPrimary()
        def area = screen.getVisualBounds()
        double w = Math.min( 360d, area.getWidth() )
        double h = Math.min( Math.max( window.getHeight(), 440d ), area.getHeight() )
        double x = window.getX() + window.getWidth() + 8
        if (x + w > area.getMaxX()) {
            double left = window.getX() - 8 - w
            x = left >= area.getMinX() ? left : area.getMaxX() - w
        }
        panel.setWidth( w )
        panel.setHeight( h )
        panel.setX( x )
        panel.setY( Math.max( area.getMinY(), Math.min( window.getY(), area.getMaxY() - h ) ) )
        refreshNow() // so it opens already saying how things stand
        panel.show()
    }
    dialog.showingProperty().addListener { obs, was, showing -> if (!showing) { panel?.hide() } }
}

// Function: Outline in red the tick box of each selected image with a problem, with the problems as its
// tooltip. problemsWith( name ) lists an image's problems (none if it has none). The ring is drawn over the
// box's own edge and just outside it, so it takes no room and nothing moves as it comes and goes. The
// current image's box keeps its black border
def outlineImageProblems( List imageCBs, String currentImageName, Closure problemsWith ) {
    String currentStyle = "-fx-border-color: black; -fx-border-width: 2; -fx-border-radius: 1;"
    imageCBs.each { cb ->
        List problems = cb.isSelected() ? (problemsWith( cb.getText() ) ?: []) : []
        boolean isCurrent = cb.getText() == currentImageName
        String ring = "-fx-background-color: red, -fx-inner-border, -fx-body-color;" +
                      "-fx-background-insets: ${isCurrent ? -2 : -1}, 1, 2; -fx-background-radius: 4, 2, 1;"
        cb.lookup( ".box" )?.setStyle( (isCurrent ? currentStyle : "") + (problems ? ring : "") )
        problems ? setTT( cb, problems.join( "\n" ) ) : cb.setTooltip( null )
    }
}


// Function: Make a box with checkboxes and a modifiable header
def makeCheckboxBox( String title, String keyWord = null, List objects, int prefWidth = 0, int prefHeight = 0, String emptyMessage = "None available" ) {
    
    def checkboxBox = new VBox( 5 )
    checkboxBox.setPadding( new Insets( 5 ) )
    if (prefWidth > 0) {
        checkboxBox.setPrefWidth( prefWidth )
        checkboxBox.setMinWidth( prefWidth )
        checkboxBox.setMaxWidth( prefWidth )
    }
    if (prefHeight > 0) {
        checkboxBox.setPrefHeight( prefHeight )
    }
    
    def checkboxContainer = new VBox( 5 )
    checkboxContainer.setPadding( new Insets( 5 ) )
    
    // Build checkboxes from supplied objects
    def checkboxes = objects.collect { obj ->

        def cb = new CheckBox( obj.name )
        cb.setUserData( obj.data ?: obj.name )
        cb.setMnemonicParsing( false ) // so no characters are dropped

        return cb
    }

    def noneAvailableLabel = new Label( emptyMessage )
    noneAvailableLabel.setStyle( "-fx-font-style: italic; -fx-text-fill: gray;" )
    noneAvailableLabel.setPadding( new Insets( 5 ) )

    checkboxContainer.getChildren().add( noneAvailableLabel )
    checkboxes.each { checkboxContainer.getChildren().add( it ) }

    // Shows "None available" whenever there are no checkboxes, or none are currently visible
    def updateNoneAvailable = {
        boolean anyVisible = checkboxes.any { it.isVisible() }
        noneAvailableLabel.setVisible( !anyVisible )
        noneAvailableLabel.setManaged( !anyVisible )
    }
    updateNoneAvailable()
    checkboxes.each { cb -> cb.visibleProperty().addListener { obs, oldVal, newVal -> updateNoneAvailable() } }
    
    def selectAllCB = makeSelectAllCB( checkboxes )
    
    def scrollPane = new ScrollPane( checkboxContainer )
    VBox.setVgrow( scrollPane, Priority.ALWAYS )
    pinCheckboxesWhileScrolling( scrollPane, checkboxes )
    
    def titleLabel = new Label()
    titleLabel.setPadding( new Insets( 5, 0, 0, 0 ) )

    // Set initial wording so the box's purpose is clear even when there's nothing to check
    titleLabel.setText( "${title.capitalize()} [0]" )
    selectAllCB.setText( "${title.capitalize()} [0]" )

    // Floor the header at its widest count, so the count changing (e.g. [9] -> [10]) never nudges it.
    // Only a floor (minWidth), never a fixed width: the first box can be measured before the app's font
    // is applied, and a fixed width would then clip the title for good
    boolean selectAllFloorSet = false
    selectAllCB.widthProperty().addListener { obs, oldW, newW ->
        if (!selectAllFloorSet && newW.doubleValue() > 0) {
            selectAllFloorSet = true
            Platform.runLater {
                String initialText = selectAllCB.getText()
                selectAllCB.setText( "${title.capitalize()} [${checkboxes.size()}]" )
                selectAllCB.applyCss()
                double w = selectAllCB.prefWidth( -1 )
                double h = selectAllCB.prefHeight( w )
                selectAllCB.setText( initialText )

                selectAllCB.setMinWidth( w )
                selectAllCB.setMinHeight( h )
            }
        }
    }

    // Add listeners to checkboxes (for changing instructions/labels). Hidden boxes don't count as selected
    String TTword = keyWord ?: title
    def refreshLabels = {
        def selectedNow = checkboxes.findAll { it.isSelected() && it.isVisible() }.collect { it.getText() }
        titleLabel.setText( "${title.capitalize()} [${selectedNow.size()}]" )
        
        selectAllCB.setText( "${title.capitalize()} [${selectedNow.size()}]" )
        setTT( selectAllCB, selectAllCB.isSelected() ? "Deselect all ${TTword.uncapitalize()}" : "Select all ${TTword.uncapitalize()}" )
        
        String tooltipText = "Select ${TTword.uncapitalize()}"
        
        if (selectedNow.size() > 0) {
            tooltipText = "${TTword.capitalize()} selected:"
            selectedNow.eachWithIndex { item, i ->
                tooltipText += "\n ${i+1}. ${item}"
            }
        }
        
        setTT( [titleLabel, scrollPane], tooltipText )
    }
    checkboxes.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal -> refreshLabels() }
        cb.textProperty().addListener { obs, oldVal, newVal -> refreshLabels() }
        
        // Hidden boxes don't count as selected, so showing/hiding one re-fires its selection listeners
        cb.visibleProperty().addListener { obs, oldVal, newVal ->
            cb.setSelected( !cb.isSelected() )
            cb.setSelected( !cb.isSelected() )
        }
    }
    refreshLabels()
    
    // Allowing shift + click to select/deselect multiple
    CheckBox lastClicked = null   
    checkboxes.eachWithIndex { cb, idx ->
    
        cb.addEventHandler( MouseEvent.MOUSE_CLICKED ) { e ->
    
            if (e.isShiftDown() && lastClicked != null) {
                int lastIdx = checkboxes.indexOf( lastClicked )
                int start = Math.min( lastIdx, idx )
                int end   = Math.max( lastIdx, idx )
    
                // JavaFX doesn't tick a box on a shift-click, so the range copies the box clicked before it
                boolean value = lastClicked.isSelected()
    
                (start..end).each { i ->
                    def other = checkboxes[ i ] // never reassign cb: this handler would keep it, and anchor later clicks on the wrong box
                    if (!other.isDisabled() && other.isVisible()) {
                        other.setSelected( value )
                    }
                }
            }
    
            lastClicked = cb
        }
    }
    
    def clearBtn = new Button()
    addIcon( clearBtn, FontAwesome.Glyph.TRASH, 14 )
    clearBtn.setOnAction { checkboxes.each { it.setSelected( false ) } }
    setTT( clearBtn, "Clear selected items" )
    
    def topRow = new HBox( 5 )
    topRow.setPadding( new Insets( 8 ) )
    
    def topRowSpacer = new Region()
    HBox.setHgrow( topRowSpacer, Priority.ALWAYS )
    
    
    checkboxBox.getChildren().addAll( topRow, scrollPane )
    
    checkboxBox.setStyle( "-fx-border-color: #000000" )  
    topRow.setStyle( "-fx-border-color: #000000; -fx-border-width: 0 0 1 0" )    
    scrollPane.setStyle( "-fx-background-color: transparent" )
    
    // Width needed to show the widest item without clipping, never less than the requested width.
    // Measures every checkbox, hidden ones included, so filtering the list never changes the width
    def naturalWidth = {
        double widest = noneAvailableLabel.prefWidth( -1 )
        checkboxes.each { cb ->
            cb.applyCss()
            double w = cb.prefWidth( -1 )
            // A checkbox whose text changes (e.g. a count that follows the selected images) can name the
            // widest text it may ever show, as a closure taking its font, so the box doesn't resize with it
            def widestText = cb.getProperties().get( "widestText" )
            if (widestText != null) {
                w += textWidth( widestText( cb.getFont() ), cb.getFont() ) - textWidth( cb.getText(), cb.getFont() )
            }
            widest = Math.max( widest, w )
        }
        def vBar = scrollPane.lookupAll( ".scroll-bar" ).find { it instanceof ScrollBar && it.orientation == Orientation.VERTICAL }
        double barW = (vBar != null && vBar.prefWidth( -1 ) > 0) ? vBar.prefWidth( -1 ) : 15 // always leave room for the bar
        double listW = widest + insetsW( checkboxContainer ) + insetsW( scrollPane ) + barW
        double contentW = Math.max( listW, topRow.prefWidth( -1 ) ) + insetsW( checkboxBox )
        return Math.ceil( Math.max( prefWidth, contentW ) )
    }

    return [
        box         : checkboxBox,
        header      : topRow,
        selectAll   : selectAllCB,
        title       : titleLabel,
        spacer      : topRowSpacer,
        clear       : clearBtn,
        CBs         : checkboxes,
        naturalWidth: naturalWidth,
        minWidth    : prefWidth > 0 ? prefWidth : 150 // narrowest the box may shrink to when the screen is too small
    ]
}

// Function: Keep each checkbox's box pinned at the left edge while its text scrolls horizontally. The
// text is drawn by a clipped Label set as the checkbox's graphic: the checkbox keeps its own text (so
// anything reading getText() is unaffected), it just doesn't draw it. Scrolling slides the whole
// checkbox right by the scroll offset and its Label back left by the same amount, so the words move
// with the scroll while the box stays put, and the clip hides the words once they reach the box
def pinCheckboxesWhileScrolling( ScrollPane scrollPane, List checkboxes ) {
    List labels = checkboxes.collect { cb ->
        def label = new Label()
        label.textProperty().bind( cb.textProperty() )
        // A graphic doesn't get the gap a checkbox normally leaves before its text, so add it back
        label.layoutXProperty().bind( Bindings.createDoubleBinding( { cb.getLabelPadding().getLeft() }, cb.labelPaddingProperty() ) )
        def holder = new Pane( label )
        def clip = new javafx.scene.shape.Rectangle()
        clip.xProperty().bind( label.layoutXProperty() ) // scrolled words vanish at the usual gap, not right against the box
        clip.widthProperty().bind( holder.widthProperty().subtract( label.layoutXProperty() ) )
        clip.heightProperty().bind( holder.heightProperty() )
        holder.setClip( clip )
        cb.setGraphic( holder )
        cb.setContentDisplay( ContentDisplay.GRAPHIC_ONLY )
        return label
    }

    // The scroll pane scrolls by shifting a wrapper around the content to a pixel-snapped position.
    // Follow that exact position rather than recomputing it from the scroll value — any rounding
    // difference between the two makes the boxes jiggle as you scroll
    def content = scrollPane.getContent()
    def update = {
        double offset = -(content.getLayoutX() + (content.getParent()?.getLayoutX() ?: 0))
        checkboxes.eachWithIndex { cb, i ->
            cb.setTranslateX( offset )
            labels[ i ].setTranslateX( -offset )
        }
    }
    content.layoutXProperty().addListener { obs, oldVal, newVal -> update() }
    content.parentProperty().addListener { obs, oldParent, newParent ->
        newParent?.layoutXProperty()?.addListener { o, oldVal, newVal -> update() }
        update()
    }
}

// Function: Rendered width of a string in a given font
def textWidth( text, font ) {
    def t = new javafx.scene.text.Text( text as String )
    t.setFont( font )
    return t.getLayoutBounds().getWidth()
}

// Function: The widest-rendering number with as many digits as max (digit glyphs differ in width, so
// e.g. "1" is narrower than "0") — for reserving room for a count that can grow up to max
def widestNumber( max, font ) {
    String widestDigit = ('0'..'9').max { textWidth( it, font ) }
    return widestDigit * "${max}".length()
}

// Function: Horizontal padding + border of a region
def insetsW( Region region ) {
    def ins = region.getInsets()
    return ins.getLeft() + ins.getRight()
}

// Function: Size each checkbox box to its widest item, widening the dialog to fit — but never past the
// edge of the screen; if everything can't fit, the widest boxes give up width first (down to their
// minimum, or 100px on a screen too small even for that) and their lists scroll horizontally.
// Re-fits whenever an item's text changes. Takes a closure returning the current box maps so rebuilt
// boxes are picked up; returns a closure that requests a re-fit (call it after rebuilding boxes)
def fitBoxesToScreen( Dialog dialog, Closure currentBoxes ) {
    boolean pending = false
    Double decoration = null // window frame width; measured once, as the scene lags behind window resizes
    def refit
    def requestRefit = {
        if (pending) { return }
        pending = true
        Platform.runLater { pending = false; refit() } // coalesce bursts of text changes into one pass
    }

    refit = {
        def window = dialog.getDialogPane().getScene()?.getWindow()
        if (window == null || !window.isShowing()) { return }
        List boxes = currentBoxes().findAll { it != null }

        boxes.each { b ->
            // The header's count floor (see makeCheckboxBox) lands a pulse after showing and can widen it
            if (!b.selectAll.getProperties().containsKey( "fitBoxesListener" )) {
                b.selectAll.getProperties().put( "fitBoxesListener", true )
                b.selectAll.minWidthProperty().addListener { obs, oldVal, newVal -> requestRefit() }
            }
            // Boxes that show a count title in their header (rather than the select-all) widen with it
            if (b.header.getChildren().contains( b.title ) && !b.title.getProperties().containsKey( "fitBoxesListener" )) {
                b.title.getProperties().put( "fitBoxesListener", true )
                b.title.textProperty().addListener { obs, oldVal, newVal -> requestRefit() }
            }
            b.CBs.each { cb ->
                if (!cb.getProperties().containsKey( "fitBoxesListener" )) {
                    cb.getProperties().put( "fitBoxesListener", true )
                    cb.textProperty().addListener { obs, oldVal, newVal -> requestRefit() }
                }
            }
        }

        // No max width, so a box can still stretch into spare room (e.g. when it's the BorderPane centre)
        def setBoxWidth = { b, double w ->
            b.box.setMinWidth( w )
            b.box.setPrefWidth( w )
            b.box.setMaxWidth( Double.MAX_VALUE )
        }

        List naturals = boxes.collect { it.naturalWidth() as double }
        boxes.eachWithIndex { b, i -> setBoxWidth( b, naturals[ i ] ) }

        def pane = dialog.getDialogPane()
        pane.applyCss()
        if (decoration == null) { decoration = Math.max( 0, window.getWidth() - window.getScene().getWidth() ) }
        double needed = pane.prefWidth( -1 ) + decoration

        def screen = Screen.getScreensForRectangle( window.getX(), window.getY(), window.getWidth(), window.getHeight() )
                .find() ?: Screen.getPrimary()
        def bounds = screen.getVisualBounds()
        double available = bounds.getWidth() - 20

        if (needed > available) {
            // Find the cap level where capping every box at max(level, its minimum) absorbs the excess.
            // Repeated, since boxes stacked in a column only free up the width of the widest of them
            List floors = (0..<boxes.size()).collect { i -> Math.min( boxes[ i ].minWidth as double, naturals[ i ] as double ) }
            def widthsAt = { List fl, double level -> (0..<naturals.size()).collect { i -> Math.min( naturals[ i ] as double, Math.max( level, fl[ i ] as double ) ) } }
            double target = naturals.sum()
            for (int pass = 0; pass < 6 && needed > available + 0.5; pass++) {
                target -= (needed - available)
                if (floors.sum() > target) { floors = floors.collect { Math.min( it as double, 100d ) } } // tiny screen
                double lo = 0, hi = naturals.max()
                30.times {
                    double mid = (lo + hi) / 2
                    if (widthsAt( floors, mid ).sum() > target) { hi = mid } else { lo = mid }
                }
                List capped = widthsAt( floors, lo )
                boxes.eachWithIndex { b, i -> setBoxWidth( b, Math.floor( capped[ i ] as double ) ) }
                pane.applyCss()
                needed = pane.prefWidth( -1 ) + decoration
            }
            needed = Math.min( needed, available )
        }

        // Grow (or shrink) around the window's current centre, then keep it on screen
        double oldW = window.getWidth()
        if (Math.abs( needed - oldW ) < 1) { return }
        double x = window.getX() - (needed - oldW) / 2
        window.setWidth( needed )
        x = Math.min( x, bounds.getMaxX() - needed )
        window.setX( Math.max( x, bounds.getMinX() ) )
    }

    dialog.setOnShown { requestRefit() }
    return requestRefit
}



// Function: Partition image entries by a keep-predicate evaluated against each image's class
// counts from summary.json. The parallel read touches no image data; the predicate runs on the
// calling thread (never a pool thread). Fails OPEN — if an image's summary is missing/unreadable
// its counts are unknown and the image is kept.
def filterImagesBySummary( List imageEntries, Closure keepPredicate ) {
    if (imageEntries.isEmpty()) { return [toRun: [], skipped: []] }

    def gson = new Gson()
    def nThreads = Math.max( 1, Math.min( Runtime.getRuntime().availableProcessors(), imageEntries.size() ) )
    def pool = Executors.newFixedThreadPool( nThreads )
    def countsList
    try {
        def futures = imageEntries.collect { entry ->
            pool.submit({
                def summaryFile = entry.getEntryPath().resolve( "summary.json" )
                if (!Files.exists( summaryFile )) { return null }   // unknown => keep
                try {
                    def raw = new String( Files.readAllBytes( summaryFile ) )
                    def summary = gson.fromJson( raw, Object )
                    def classCounts = summary?.get( "hierarchy" )?.get( "annotationClassificationCounts" )
                    if (!(classCounts instanceof Map)) { return null }
                    def counts = [:]
                    classCounts.each { k, v -> counts[ k.toString() ] = (v instanceof Number) ? ((Number) v).intValue() : 0 }
                    return counts
                } catch (Exception e) {
                    logger.warn( "[AnnoTools] class pre-filter: summary parse failed for ${entry.getImageName()}: ${e.getMessage()}" )
                    return null   // unknown => keep
                }
            } as Callable)
        }
        countsList = futures.collect { it.get() }
    } finally {
        pool.shutdown()
    }

    def toRun   = []
    def skipped = []
    imageEntries.eachWithIndex { entry, i ->
        def counts = countsList[ i ]
        boolean keep = (counts == null) ? true : keepPredicate( counts )
        (keep ? toRun : skipped) << entry
    }
    return [toRun: toRun, skipped: skipped]
}


// Function: Natural (alphanumeric) comparison so embedded numbers sort numerically rather than lexically
def naturalCompare( String s1, String s2 ) {
    def ta = []
    (( s1 ?: "" ).toLowerCase() =~ /\d+|\D+/).each { ta << it }
    def tb = []
    (( s2 ?: "" ).toLowerCase() =~ /\d+|\D+/).each { tb << it }
    int n = Math.min( ta.size(), tb.size() )
    for (int i = 0; i < n; i++) {
        String x = ta[ i ]
        String y = tb[ i ]
        int cmp = (x ==~ /\d+/ && y ==~ /\d+/) ? ((x as BigInteger) <=> (y as BigInteger)) : (x <=> y)
        if (cmp != 0) { return cmp }
    }
    return ta.size() <=> tb.size()
}


// Function: The class label used to identify an object by class. Mirrors QuPath's project
// summary (DefaultProject.HierarchySummary.pathClassToString), which the class lists come from: the
// full name, so a subclass is "Tumor: Positive" rather than getName()'s "Positive", and "Unclassified"
// for an object with no class. A label that differs would be selectable but never match.
def classLabelForObject( obj ) {
    return obj.getPathClass()?.toString() ?: "Unclassified"
}


// Project & preferences file
def project = getProject()
def currentImageData = getCurrentImageData()
def currentEntry = currentImageData ? project.getEntry( currentImageData ) : null

// Each pane of a split viewer holds its own live ImageData. Editing a background copy read from disk
// leaves the other panes drawing the objects they had before the run — and lets a later save from one
// of them write that stale hierarchy back over this run's changes. So any image open in *any* viewer
// is edited through the instance that viewer holds, not through a copy.
def viewersByEntryId = [:]   // entry ID -> viewers currently showing that image
runOnFxThreadAndWait {
    QuPathGUI.getInstance().getAllViewers().each { viewer ->
        def viewerData = viewer.getImageData()
        if (viewerData == null) { return }
        def viewerEntry = project.getEntry( viewerData )
        if (viewerEntry == null) { return }
        viewersByEntryId.get( viewerEntry.getID(), [] ) << viewer
    }
}

// Viewers showing an image right now — a pane the user re-points mid-run stops counting
def viewersForEntry = { entry ->
    if (entry == null) { return [] }
    ( viewersByEntryId[ entry.getID() ] ?: [] ).findAll { viewer ->
        def viewerData = viewer.getImageData()
        viewerData != null && project.getEntry( viewerData )?.getID() == entry.getID()
    }
}

// The live ImageData a viewer holds for this image, or null when no pane is showing it
def liveDataForEntry = { entry ->
    def viewers = viewersForEntry( entry )
    viewers ? viewers[ 0 ].getImageData() : null
}

// Annotation class counts taken straight from a live hierarchy
def liveClassCounts = { imgData ->
    def counts = [:]
    imgData.getHierarchy().getAnnotationObjects().each { obj ->
        def cn = classLabelForObject( obj )
        counts[ cn ] = (counts[ cn ] ?: 0) + 1
    }
    return counts
}
def projectDirectory = project.getBaseDirectory()

// Folder and file names of where preferences are stored in directory
def scriptPrefsFolderName = "AnnoTools-Data"
def scriptPrefsFileName   = "Remove-Annos_Preferences" + ".json"

def prefsFolder = new File( projectDirectory, scriptPrefsFolderName )
if (!prefsFolder.exists()) { prefsFolder.mkdirs() }

def prefsFile = new File( prefsFolder, scriptPrefsFileName )
if (!prefsFile.exists()) { prefsFile.createNewFile() }

// Read previous preferences
def prePrefsMap = [:]
if (prefsFile.length() > 0L && prefsFile.getText() != "null") {
    def inputGson = new Gson()
    def readDataFile = inputGson.fromJson( prefsFile.getText(), Object )
    if (readDataFile instanceof Map) {
        prePrefsMap = readDataFile
    }
}


// --- PREFERENCES POPUP ---
def codePreferences = runOnFxThreadAndWait {
    
    def dialog = new Dialog()
    dialog.setTitle( "Which annotations should be removed?" )
    
    def root = new BorderPane()
    root.setPrefHeight( 350 )
    
    dialog.getDialogPane().buttonTypes.setAll( ButtonType.OK, ButtonType.CANCEL )
    def okBtn = dialog.getDialogPane().lookupButton( ButtonType.OK )
    okBtn.setText( "Remove Objects" )
    ButtonBar.setButtonUniformSize( okBtn, false )
    
    
    
    // RIGHT PANEL: PathClass selection list
    def gson = new Gson()
    def imageClassCounts = [:]  // imageName -> [className -> count]
    def totalsByClass = [:]     // className -> total across all images
    
    def imageList = project.getImageList()
    def nThreads = Math.min( Runtime.getRuntime().availableProcessors(), imageList.size() )
    def pool = Executors.newFixedThreadPool( nThreads )
    
    def futures = imageList.collect { entry ->
        pool.submit({
            def summaryFile = entry.getEntryPath().resolve( "summary.json" )
            def counts = [:]
            if (Files.exists( summaryFile )) {
                try {
                    def raw = new String( Files.readAllBytes( summaryFile ) )
                    def summary = gson.fromJson( raw, Object )
                    def classCounts = summary?.get( "hierarchy" )?.get( "annotationClassificationCounts" )
                    classCounts?.each { k, v ->
                        counts[ k.toString() ] = (v instanceof Number) ? ((Number)v).intValue() : 0
                    }
                } catch (Exception e) {
                    logger.warn( "[AnnoTools] summary parse failed for ${entry.getImageName()}: ${e.getMessage()}" )
                }
            }
            return [entry.getImageName(), counts]
        } as Callable)
    }
    
    // Serial merge directly into imageClassCounts and totalsByClass, no intermediate map needed
    futures.each { f ->
        def (imgName, counts) = f.get()
        imageClassCounts[ imgName ] = counts
        counts.each { className, count ->
            totalsByClass.merge( className, count, Integer.&sum )
        }
    }
    pool.shutdown()
    
    // If an image is open in a viewer, use its LIVE hierarchy for that image's class counts so
    // unsaved objects are reflected — both in the counts and in whether a class appears at all.
    // Every pane of a split viewer counts, not just the selected one, since the summary.json read
    // above only knows what was last saved. Cheap, since those images are already loaded, and safe
    // on the FX thread we're already running on.
    boolean anyLiveCounts = false
    project.getImageList().each { entry ->
        def liveData = liveDataForEntry( entry )
        if (liveData == null) { return }
        imageClassCounts[ entry.getImageName() ] = liveClassCounts( liveData )
        anyLiveCounts = true
    }

    // Rebuild totals in place so the overrides are reflected everywhere downstream
    if (anyLiveCounts) {
        totalsByClass.clear()
        imageClassCounts.each { imgName, counts ->
            counts.each { className, count ->
                totalsByClass.merge( className, count, Integer.&sum )
            }
        }
    }

    // Only classes with existing annotations in project, sorted by abundance
    List pathClassCBsMap = totalsByClass
        .collect { [name: it.key, data: it.key, num: it.value] }
        .sort { a, b -> (b.num <=> a.num) ?: naturalCompare( a.name, b.name ) }
        .collect { [name: it.name, data: it.data] } // labels set by updateClassCounts
    def pathClassBox = makeCheckboxBox( "Object classes", null, pathClassCBsMap, 200 )    
    pathClassBox.header.getChildren().addAll( pathClassBox.selectAll )    
    pathClassBox.CBs.each { it.setSelected( it.getUserData() in prePrefsMap.objectClasses ) }
    
    root.right = pathClassBox.box
    
    
    
    // CENTER PANEL: Image Selection
    def imageNames = project.getImageList().collect { it.getImageName() }
    def imagesBox = makeCheckboxBox( "Select images", "images", imageNames.collect { [name: it] } )
    def currentImageCB = new CheckBox( "Current Image" )
    imagesBox.header.getChildren().addAll( imagesBox.selectAll, imagesBox.spacer, currentImageCB )
    imagesBox.CBs.each { it.setSelected( it.getText() in prePrefsMap.selectedImages  ) }
    BorderPane.setMargin( imagesBox.box, new Insets( 0, 15, 0, 0 ) )
    
    if (currentEntry) {
        def currentCB = imagesBox.CBs.find { it.getText() == currentEntry.getImageName() }
        currentImageCB.selectedProperty().bindBidirectional(
            currentCB.selectedProperty()
        )
        
        // Setting bright border on the "Current Image" checkbox (the list's own is set by outlineImageProblems)
        Platform.runLater { currentImageCB.lookup( ".box" )?.setStyle( "-fx-border-color: black; -fx-border-width: 2; -fx-border-radius: 1;" ) }
        
        // If current image was only one selected, will update to new image
        if (prePrefsMap.currentImage == true && prePrefsMap.selectedImages.size() == 1) {
            imagesBox.CBs.each { it.setSelected( false ) }
            currentImageCB.setSelected( true )
        }
    } else {
        currentImageCB.setDisable( true )
        explainWhenDisabled( currentImageCB ) { "No image is open in the viewer" }
    }
    
    // Setting and updating tooltip for current image checkbox
    currentImageCB.selectedProperty().addListener { obs, oldVal, newVal ->
        setTT( currentImageCB, currentImageCB.isSelected() ? "Deselect current image" : "Select current image" )
    }
    
    root.center = imagesBox.box
    
    
    
    // Closure to update total and selected class counts
    def updateClassCounts = {
        def selectedNames = imagesBox.CBs.findAll { it.isSelected() }.collect { it.getText() } as Set
        def selectedTotals = [:]
        selectedNames.each { imgName ->
            imageClassCounts[ imgName ]?.each { className, count ->
                selectedTotals[ className ] = (selectedTotals[ className ] ?: 0) + count
            }
        }
        pathClassBox.CBs.each { cb ->
            def className = cb.getUserData()
            def sel   = selectedTotals[ className ] ?: 0
            def total = totalsByClass[ className ]  ?: 0
            cb.setText( total > 0 ? "${className} [${sel} / ${total}]" : className )
            // Reserve room for the widest the count can read, so selecting images doesn't resize the box
            cb.getProperties().put( "widestText", { font -> total > 0 ? "${className} [${widestNumber( total, font )} / ${total}]" : className } )
        }
    }
    
    imagesBox.CBs.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal -> updateClassCounts() }
    }
    updateClassCounts()

    // Red ring on each selected image without objects of the selected classes (only once a class is
    // chosen; until then the class box itself is flagged)
    def updateImageFlags = {
        def chosen = pathClassBox.CBs.findAll { it.isSelected() }*.getUserData()
        outlineImageProblems( imagesBox.CBs, currentEntry?.getImageName() ) { String name ->
            chosen && !chosen.any { imageClassCounts[ name ]?.get( it ) } ? ["No objects of the selected classes to remove"] : []
        }
    }
    (imagesBox.CBs + pathClassBox.CBs).each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal -> updateImageFlags() }
    }
    Platform.runLater { updateImageFlags() } // tick boxes are only built once the dialog shows
    
    
    
    // Disabling the "OK" button if not all inputs are met    
    def anySelectedImage = Bindings.createBooleanBinding(
        { imagesBox.CBs.any { it.isSelected() } },
        *imagesBox.CBs.collect { it.selectedProperty() }
    )
    def anySelectedPathClass = Bindings.createBooleanBinding(
        { pathClassBox.CBs.any { it.isSelected() } },
        *pathClassBox.CBs.collect { it.selectedProperty() }
    )
    
    okBtn.disableProperty().bind(
        anySelectedImage.not()
        .or( anySelectedPathClass.not() )
    )

    // Red outline on each input while it is a reason the "OK" button is disabled
    flagWhenBlocking( imagesBox.box, anySelectedImage.not() )
    flagWhenBlocking( pathClassBox.box, anySelectedPathClass.not() )

    // Say why on hover, while disabled
    explainWhenDisabled( okBtn ) { [
        "Select at least one image"       : !anySelectedImage.get(),
        "Select at least one object class": !anySelectedPathClass.get()
    ] }

    // Requesting confirmation to remove
    boolean confirmRemove = false
    okBtn.addEventFilter( ActionEvent.ACTION ) { e ->
        def confirmRemoveAlert = new Alert( Alert.AlertType.CONFIRMATION )
        
        confirmRemoveAlert.setTitle( "Confirm annotation removal" )  
        confirmRemoveAlert.setHeaderText( null )
        confirmRemoveAlert.setContentText( "Removed annotations and children cannot be recovered. Continue?" )
        
        def result = confirmRemoveAlert.showAndWait()
        def selection = result.isPresent() && result.get() == ButtonType.OK
        
        if (!selection) {
            e.consume()
            return
        }
        
        confirmRemove = true
    }
    
    // "?" button: what each input does. Each entry's now: says how that input stands at the moment, read
    // from the dialog every time the help panel refreshes
    def chosenImages  = { imagesBox.CBs.findAll { it.isSelected() }*.getText() }
    def chosenClasses = { pathClassBox.CBs.findAll { it.isSelected() }*.getUserData() }
    def annosIn = { String imgName, List classes -> (classes.sum { imageClassCounts[ imgName ]?.get( it ) ?: 0 } ?: 0) as int }
    def some    = { int n, String one, String many -> n == 1 ? "1 ${one}" : "${n} ${many}" }
    def listOut = { List xs -> xs.size() < 2 ? xs.join() : xs[ 0..-2 ].join( ", " ) + " and " + xs[ -1 ] } // "a, b and c"
    def imagesNow = { String ifNone ->
        List images = chosenImages(), classes = chosenClasses()
        if (!imagesBox.CBs) { return "No images in the project. Add images to the project first." }
        if (!images) { return "No images selected yet." }
        int without = classes ? images.count { annosIn( it, classes ) == 0 } : 0
        "${images.size()} of ${some( imagesBox.CBs.size(), 'image', 'images' )} selected." + (
            without == 0             ? "" :
            without == images.size() ? " None of them has annotations of the selected classes." :
                                       " ${without} of them ${without == 1 ? 'has' : 'have'} no annotations of the selected classes${ifNone}." )
    }
    def classesNow = {
        List images = chosenImages(), classes = chosenClasses()
        if (!pathClassBox.CBs) { return "No annotations in the project yet, so there are no classes to choose from. Draw or import annotations first." }
        if (!classes) { return "No classes selected yet." }
        String chosen = "${some( classes.size(), 'class', 'classes' )} selected"
        images ? chosen + ", with ${some( images.sum { annosIn( it, classes ) } as int, 'annotation', 'annotations' )} in the selected images." :
                 chosen + ". Select images to see how many annotations that covers."
    }

    addHelpButton( dialog, "Remove annotations",
        "Deletes every annotation of the classes you choose from the images you choose, along with everything nested inside them.",
        [
            [ title: "Select images",
              nodes: [imagesBox.box],
              text : "The images to remove annotations from." +
                     "\nTop box: selects or clears them all. Current Image: selects the image open in the viewer." +
                     "\nShift-click: click one box, then shift-click another to set every box between them to match the first." +
                     "\nOutlined in red: a selected image with no annotations of the chosen classes, which will be skipped.",
              now  : { imagesNow( " and would be skipped" ) } ],
            [ title: "Object classes",
              nodes: [pathClassBox.box],
              text : "The classes of annotation to delete. The numbers show how many annotations of each class are in the selected images and in the project [images / project]." +
                     "\nAnnotations without a class are listed as Unclassified." +
                     "\nTop box: selects or clears them all.",
              now  : classesNow ],
            [ title: "Remove Objects",
              nodes: [okBtn],
              text : "Deletes the annotations, and everything nested inside them, after asking you to confirm. This can't be undone, so back up the project first if you're unsure." +
                     "\nGreyed out until an image and a class are chosen: hover over it to see what's missing.",
              now  : {
                  List images = chosenImages(), classes = chosenClasses()
                  List missing = [ images ? null : "an image", classes ? null : "an object class" ].findAll()
                  if (missing) { return "Not ready. Still needs ${listOut( missing )}." }
                  int annos = images.sum { annosIn( it, classes ) } as int
                  if (annos == 0) { return "None of the selected images has annotations of the selected classes, so there is nothing to remove." }
                  "Ready. It will delete " + (annos == 1 ? "the 1 annotation" : "all ${annos} annotations") + " of the selected classes, and " +
                      "everything nested inside, from ${some( images.count { annosIn( it, classes ) > 0 } as int, 'image', 'images' )}."
              } ]
        ] )

    // Attach root & show dialog
    // Widen boxes to their widest item (window grows to fit, up to the screen width)
    fitBoxesToScreen( dialog ) { [imagesBox, pathClassBox] }

    dialog.getDialogPane().content = root
    
    dialog.showAndWait()
    
    if (confirmRemove) {                
        def selectedImages = imagesBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getText() }
        def selectedPathClasses = pathClassBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getUserData() }
        
        // Return all preferences if "OK" is selected
        return [
            selectedImages : selectedImages,
            currentImage   : currentImageCB.isSelected(),
            objectClasses  : selectedPathClasses
        ]
        
    } else {
        return null
    }
}


// Exit if cancelling
if (codePreferences == null) {
    return
    
} else { // Remember the choices for next time
    
    def outputGson = new GsonBuilder().setPrettyPrinting().create()
    prefsFile.text = outputGson.toJson( codePreferences )
}


def imagesToRun = project.getImageList().findAll { it.getImageName() in codePreferences.selectedImages }

// --- PRE-FILTER: skip selected images with no objects of the chosen class(es) to remove ---
// Images a viewer holds are judged from their live hierarchy, so unsaved objects count; the rest from
// their lightweight summary.json snapshot (read in parallel; an unreadable one keeps its image)
def keepImage = { counts -> codePreferences.objectClasses.any { (counts[ it ] ?: 0) > 0 } }
def liveKeep = [:]   // entry ID -> keep?
imagesToRun.each { img ->
    def liveData = liveDataForEntry( img )
    if (liveData != null) { liveKeep[ img.getID() ] = keepImage( liveClassCounts( liveData ) ) }
}
def keptFromSummary = filterImagesBySummary( imagesToRun.findAll { !liveKeep.containsKey( it.getID() ) }, keepImage ).toRun as Set
def keepThisImage = { img -> liveKeep.containsKey( img.getID() ) ? liveKeep[ img.getID() ] : (img in keptFromSummary) }
def skippedImages = imagesToRun.findAll { img -> !keepThisImage( img ) }
imagesToRun       = imagesToRun.findAll { img ->  keepThisImage( img ) }

if (!skippedImages.isEmpty()) {
    def skippedNames = skippedImages.collect { it.getImageName() }
    logger.info( "[AnnoTools] Remove annotations: skipping ${skippedNames.size()} selected image(s) with no matching objects: ${skippedNames.join( ', ' )}" )
}
if (imagesToRun.isEmpty()) {
    runOnFxThreadAndWait {
        def alert = new Alert( Alert.AlertType.INFORMATION )
        alert.setTitle( "Remove annotations" )
        alert.setHeaderText( null )
        alert.setContentText( "None of the selected images contain objects of the chosen class(es) to remove.\nNothing to do." )
        def gui = QuPathGUI.getInstance()
        if (gui != null) { alert.initOwner( gui.getStage() ) }
        alert.showAndWait()
    }
    return
}
if (!skippedImages.isEmpty()) {
    Dialogs.showInfoNotification( "Remove annotations", "Skipping ${skippedImages.size()} selected image(s) with no objects of the chosen class(es)." )
}

def total = imagesToRun.size()
def progressUI = createProgressWindow( "Processing", total, imagesToRun.collect { it.getImageName() } )
def stopLaterFlag = progressUI.stopLaterFlag

def nThreads = Math.min(
    Runtime.getRuntime().availableProcessors() * 2,
    imagesToRun.size()
)
def pool = Executors.newFixedThreadPool( nThreads )

// Image a failing task was working on, so the handler below can name it — "stopped after an
// error" is useless when the run covered a hundred images. First failure wins; the rest of the
// pool is torn down anyway.
def imageInFlight = new AtomicReference<String>( null )
def onImage = { String imageName, Closure work ->
    return {
        try {
            return work()
        } catch (Throwable t) {
            imageInFlight.compareAndSet( null, imageName )
            throw t
        }
    }
}

try {
// Parallel running images
def futures = imagesToRun.findAll { viewersForEntry( it ).isEmpty() }.collect { image ->
    pool.submit( onImage( image.getImageName(), {
        if (stopLaterFlag.get()) { return } // stopped: leave the images not yet started
        def imageData = image.readImageData()
        try {
            def hierarchy = imageData.getHierarchy()
            def objectsToRemove = hierarchy.getAnnotationObjects().findAll {
                classLabelForObject( it ) in codePreferences.objectClasses
            }
            hierarchy.removeObjects( objectsToRemove, false )
            image.saveImageData( imageData )
        } finally {
            imageData.close()
        }
        
        // Write atomic state — poll timeline reads these on its own schedule
        progressUI.lastImageName.set( image.getImageName() )
        progressUI.doneCount.incrementAndGet()
    }) as Callable)
}

futures.each { it.get() }
pool.shutdown()

// Images a viewer holds are handled serially after, edited in place so every pane showing them
// stays in step — including split panes that aren't selected
imagesToRun.findAll { !viewersForEntry( it ).isEmpty() }.each { image ->
    if (stopLaterFlag.get()) { return }
    def openViewers = viewersForEntry( image )
    def liveData = openViewers[ 0 ].getImageData()
    onImage( image.getImageName(), {
        def hierarchy = liveData.getHierarchy()
        def objectsToRemove = hierarchy.getAnnotationObjects().findAll {
            classLabelForObject( it ) in codePreferences.objectClasses
        }
        // removeObjects fires a hierarchy event (and drops removed objects from the selection)
        // that QuPath's panes react to, so on live data it must run on the FX thread. Every pane
        // showing the image redraws before saving: that marks the data as having unsaved changes,
        // which the save then clears
        runOnFxThreadAndWait {
            hierarchy.removeObjects( objectsToRemove, false )
            hierarchy.fireHierarchyChangedEvent( this )
            openViewers.each { it.repaintEntireImage() }
        }
        image.saveImageData( liveData )
        progressUI.lastImageName.set( image.getImageName() )
        progressUI.doneCount.incrementAndGet()
    }).call()
}

// Brief pause so the poll timeline renders the completed state before close
Thread.sleep( 200 )
} catch (Throwable err) {
    // Runs outside the script editor, so a thrown error would be invisible: kill the pool, log it (the
    // full stack trace goes to QuPath's log), point the user there, and close the modal window so QuPath
    // isn't left blocked. Then stop.
    pool.shutdownNow()
    String failedOn = imageInFlight.get() ? " on image '${imageInFlight.get()}'" : ""
    logger.error( "Remove annotations stopped after an error" + failedOn, err )
    Dialogs.showErrorNotification( "Remove annotations", "Stopped after an error" + failedOn + ". See the log for details." )
    Platform.runLater { progressUI.stage.close() }
    return
}

// Closing progress bar window (runs on the success path)
Platform.runLater {
    progressUI.stage.close()
}


import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javafx.util.Duration
import javafx.animation.*
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.event.ActionEvent
import javafx.geometry.*
import javafx.scene.Scene
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.layout.*
import javafx.scene.input.*
import javafx.stage.*
import javafx.beans.binding.Bindings
import com.google.gson.*
import qupath.lib.gui.tools.IconFactory
import org.controlsfx.glyphfont.FontAwesome


// Comments for tool launcher icon and tooltip
// ORDER: 7
// ICON: TRASH
// Remove objects by class from project