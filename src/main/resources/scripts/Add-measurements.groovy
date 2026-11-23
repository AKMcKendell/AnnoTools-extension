/*
 * Author: Alec McKendell
 * Date: 2026.2.4
 * Description: Add intensity or morphology measurements to annotations of interest.
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
        
        def stepLabel = new Label()
        def stepNum = new SimpleStringProperty( "1" )
        def stepMax = new SimpleStringProperty( "" )
        def stepName = new SimpleStringProperty( "" )
        def stepPer = new SimpleStringProperty( "0" )      
        stepLabel.textProperty().bind(
            Bindings.concat( "Step ", stepNum, "/", stepMax, ": ", stepName, " (", stepPer, "%)" )
        )
        def stepRow = new HBox( 5, stepLabel )
        
        def imageNum = new SimpleStringProperty( "1" )
        def imageName = new SimpleStringProperty( "Starting..." )
        def imageLabel = new Label()
        imageLabel.textProperty().bind(
            Bindings.concat( "Image ", imageNum, "/", totalImages, ": ", imageName )
        )      
        def imageRow = new HBox( 5, imageLabel )
        
        def bar = new ProgressBar( 0.0 )
        def barRow = new HBox( 10, bar )  
        
        def stopBtn = new Button( "Stop" )
        def stopNowInsteadBtn = new Button( "Stop now" )
        stopBtn.setDisable( false )
        def spacer = new Region()
        HBox.setHgrow( spacer, Priority.ALWAYS )
        def timerLabel = new Label( "Elapsed: 00:00:00" )       
        def bottomRow = new HBox( 10, stopBtn, spacer, timerLabel )
        bottomRow.setAlignment( Pos.CENTER_LEFT )
        
        List rows = [stepRow, imageRow, barRow, bottomRow]
        
        def vbox = new VBox( 10 )
        vbox.getChildren().addAll( rows )
        vbox.setPadding( new Insets( 10 ) )
        vbox.setAlignment( Pos.CENTER_LEFT )
        
        // Sizing to fit all rows
        def updateMinWidth = {
            double preferred = rows.collect { it.getWidth() }.max() + vbox.getPadding().getLeft() + vbox.getPadding().getRight()
            if ( preferred > stage.getWidth() ) {
                stage.setWidth( Math.min( preferred, maxWindowWidth( stage ) ) )
            }
        }
        
        // Attaching the width update as a listener
        rows.each { node ->
            node.setMinWidth( Region.USE_PREF_SIZE )
            node.widthProperty().addListener { obs, old, nw -> updateMinWidth() }
        }
        
        // Progress bar fills row width
        bar.setMaxWidth( Double.MAX_VALUE )
        HBox.setHgrow( bar, Priority.ALWAYS )
        
        // Stage grows (never shrinks) to fit widest row
        def updateStageWidth = {
            Platform.runLater {
                double maxRowPref = rows.collect { it.prefWidth(-1) }.max()
                double needed = maxRowPref + vbox.getPadding().getLeft() + vbox.getPadding().getRight()
                if ( needed > Math.max( stage.getMinWidth(), 0 ) ) {
                    stage.setMinWidth( Math.min( needed, maxWindowWidth( stage ) ) )
                }
            }
        }
        [ stepNum, stepMax, stepName, stepPer, imageNum, imageName ].each { prop ->
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
        
        def timeline = new Timeline(
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
        timeline.setCycleCount( Animation.INDEFINITE )
        timeline.play()
        
        // Stop the timer when the stage is actually closed
        stage.setOnHidden { timeline.stop() }
        
        // Signal to stop script
        def stopNowFlag   = new AtomicBoolean( false )
        def stopLaterFlag = new AtomicBoolean( false )
        
        def stopNowLabel = new Label( "Stopping after operation..." )
        stopNowLabel.setPadding( new Insets( 4 ) )
        def stopLaterLabel = new Label( "Stopping after image..." )
        stopLaterLabel.setPadding( new Insets( 4 ) )
        
        def poolRef = new AtomicReference( null )

        def showStopConfirmation = {
            if (stopNowFlag.get() || stopLaterFlag.get()) { return }
            
            def alert = new Alert( Alert.AlertType.CONFIRMATION )
            alert.setTitle( "Stop confirmation" )
            alert.setHeaderText( "Stop processing?" )
            alert.initOwner( stage )
            
            alert.getDialogPane().buttonTypes.setAll( ButtonType.OK, ButtonType.YES, ButtonType.CANCEL )
            def stopAfterImageBtn = alert.getDialogPane().lookupButton( ButtonType.OK )
            def stopNowBtn        = alert.getDialogPane().lookupButton( ButtonType.YES )
            def backBtn           = alert.getDialogPane().lookupButton( ButtonType.CANCEL )
            stopAfterImageBtn.setText( "Stop after image" )
            stopNowBtn.setText( "Stop now" )
            backBtn.setText( "Back" )
            
            def response = alert.showAndWait().orElse( null )
            
            if (response == ButtonType.YES) {
                stopNowFlag.set( true )
                bottomRow.getChildren().set( 0, stopNowLabel )
                poolRef.get()?.shutdownNow()
            
            } else if (response == ButtonType.OK) {
                stopLaterFlag.set( true )
                bottomRow.getChildren().set( 0, stopLaterLabel )
                bottomRow.getChildren().add( 1, stopNowInsteadBtn )
            }
        }

        stopBtn.setOnAction {
            showStopConfirmation()
        }
        stage.setOnCloseRequest { evt ->
            evt.consume()
            showStopConfirmation()
        }
        stopNowInsteadBtn.setOnAction {
            stopNowFlag.set( true )
            bottomRow.getChildren().set( 0, stopNowLabel )
            bottomRow.getChildren().remove( stopNowInsteadBtn )
            poolRef.get()?.shutdownNow()
        }
        
        return [
            stage        : stage,
            stepNum      : stepNum,
            stepMax      : stepMax,
            stepName     : stepName,
            stepPer      : stepPer,
            imageNum     : imageNum,
            imageName    : imageName,
            bar          : bar,
            stopNowFlag  : stopNowFlag,
            stopLaterFlag: stopLaterFlag,
            poolRef      : poolRef
        ]
    }
}

// Function: Update progress window
def updateProgressWindow( UI, Map args = [:] ) {
    String stepNum   = args.stepNum
    String stepMax   = args.stepMax
    String stepName  = args.stepName
    String stepPer   = args.stepPer
    String imageNum  = args.imageNum
    String imageName = args.imageName
    double imagePer  = args.imagePer ?: 0

    Platform.runLater {
        if (stepNum)   { UI.stepNum.set( stepNum ) }
        if (stepMax)   { UI.stepMax.set( stepMax ) }
        if (stepName)  { UI.stepName.set( stepName ) }
        if (stepPer)   { UI.stepPer.set( stepPer ) }
        if (imageName) { UI.imageName.set( imageName ) }
        if (imageNum)  { UI.imageNum.set( imageNum ) }
        if (imagePer)  { UI.bar.setProgress( imagePer ) }
        
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
// (what is chosen, what that will do), shown under its text as "Curren: ..." and kept up to date while the
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


// Function: The base channel name, with any "(C1)"-style index suffix stripped, so channel names match
// across images that do and don't carry it ("DAPI (C1)" vs "DAPI"). Every cross-image comparison of
// channel names goes through this. (Same helper as Manual-subclassification.)
def baseChannelName( name ) {
    // Untyped parameter and an internal coercion: a typed String parameter blows up with
    // "argument type mismatch" when a closure passes it null (e.g. a channel with no name).
    String chName = name as String
    chName?.replaceAll( '\\s*\\(C\\d+\\)\\s*$', '' )?.trim() ?: chName
}


// Function: The class label used to identify an object by class. Mirrors QuPath's project
// summary (DefaultProject.HierarchySummary.pathClassToString), which the class lists come from: the
// full name, so a subclass is "Tumor: Positive" rather than getName()'s "Positive", and "Unclassified"
// for an object with no class. A label that differs would be selectable but never match.
def classLabelForObject( obj ) {
    return obj.getPathClass()?.toString() ?: "Unclassified"
}


// Function: Full-resolution intensity measurements for a single annotation, computed by TILING the
// ROI's bounding box so the whole region — and every pixel value — is never held in memory at once.
// This is what lets very large masks (e.g. collagen per layer) be measured without a Java-heap-space
// crash: QuPath's native addIntensityMeasurements reads the entire bounding box in one buffer AND keeps
// every masked pixel value (Apache DescriptiveStatistics) to compute its percentile, so a whole-layer
// mask needs many GB. This streams the same statistics tile-by-tile instead, with bounded memory.
//
// It produces the SAME measurement columns as the native method ("<channel>: Mean", "<channel>:
// Std.Dev.", ...) with matching definitions: mean, sample (n-1) variance/std dev, min, max, and a
// histogram median (exact for integer pixel types up to 16-bit, sub-quantization accuracy for
// floating-point types). `downsample` is 1.0 for full resolution; the caller passes a larger value only
// as a speed fallback for enormous masks.
// `onTileDone` (optional) is called after every tile with that tile's share of this annotation's work,
// as a fraction of 1.0 across both passes, so a large mask reports progress before it finishes.
def addIntensityMeasurementsTiled( server, anno, double downsample, Collection measurements,
                                   List channelNames, int tileSize, AtomicBoolean stopFlag,
                                   Closure onTileDone = null ) {
    def roi = anno.getROI()
    if (roi == null) { return }

    def MEAS = ObjectMeasurements.Measurements
    boolean wantMean   = measurements.contains( MEAS.MEAN )
    boolean wantMedian = measurements.contains( MEAS.MEDIAN )
    boolean wantMin    = measurements.contains( MEAS.MIN )
    boolean wantMax    = measurements.contains( MEAS.MAX )
    boolean wantStd    = measurements.contains( MEAS.STD_DEV )
    boolean wantVar    = measurements.contains( MEAS.VARIANCE )

    int nCh = channelNames.size()
    if (nCh == 0) { return }

    // Per-channel streaming accumulators (mean/std/min/max computed from these, no per-pixel retention)
    long[]   count = new long[ nCh ]
    double[] sum   = new double[ nCh ]
    double[] sumSq = new double[ nCh ]
    double[] minV  = new double[ nCh ]
    double[] maxV  = new double[ nCh ]
    for (int c = 0; c < nCh; c++) {
        minV[ c ] = Double.POSITIVE_INFINITY
        maxV[ c ] = Double.NEGATIVE_INFINITY
    }

    // Median needs a histogram (sorting every value is the memory cost we're avoiding). Integer types
    // up to 16-bit get an exact histogram over their whole value range, filled in the same pass;
    // anything else gets a second pass over [min, max] once those are known.
    def pixelType = server.getPixelType()
    boolean intHist = wantMedian && !pixelType.isFloatingPoint() && pixelType.getBitsPerPixel() <= 16
    int histLo   = 0
    int histSize = 0
    long[][] hist = null
    if (intHist) {
        histLo   = pixelType.getLowerBound().intValue()
        histSize = pixelType.getUpperBound().intValue() - histLo + 1
        hist     = new long[ nCh ][ histSize ]
    }

    // ROI bounding box in full-resolution pixels, clipped to the image
    int x0 = Math.max( 0, (int) Math.floor( roi.getBoundsX() ) )
    int y0 = Math.max( 0, (int) Math.floor( roi.getBoundsY() ) )
    int x1 = Math.min( server.getWidth(),  (int) Math.ceil( roi.getBoundsX() + roi.getBoundsWidth() ) )
    int y1 = Math.min( server.getHeight(), (int) Math.ceil( roi.getBoundsY() + roi.getBoundsHeight() ) )
    int z = roi.getZ()
    int t = roi.getT()
    String path = server.getPath()

    // The ROI's fill shape, built ONCE. roi.getShape() would wrap the geometry in a java.awt.geom.Area
    // — one of the most memory-hungry objects in Java2D for a complex per-layer mask — so we take the raw
    // Path2D from geometryToShape instead. It's the exact shape getShape() wraps (same winding), so
    // createShapeMask's Graphics2D.fill produces the same mask, holes included, at a fraction of the
    // memory. (QuPath's createROIMask would rebuild that Area for every tile.)
    def maskShape = GeometryTools.geometryToShape( roi.getGeometry() )

    // Reusable per-tile buffers. This method runs on a single pooled thread per annotation, so these
    // are private to it — the parallelism is across annotations, not within one.
    int cap = tileSize * tileSize
    int[]    maskBuf = new int[ cap ]
    double[] valBuf  = new double[ cap ]

    // Share of this annotation's work carried by one tile. The float-median case walks the tile grid
    // twice, so each tile there is worth half as much.
    long nTilesX = (long) Math.ceil( Math.max( 0, x1 - x0 ) / (double) tileSize )
    long nTilesY = (long) Math.ceil( Math.max( 0, y1 - y0 ) / (double) tileSize )
    long nTiles  = Math.max( 1L, nTilesX * nTilesY )
    double tileWeight = 1.0d / ( nTiles * ( (wantMedian && !intHist) ? 2.0d : 1.0d ) )

    // --- Pass 1: mean / std / min / max (+ integer-histogram median in the same pass) ---
    for (int ty = y0; ty < y1; ty += tileSize) {
        for (int tx = x0; tx < x1; tx += tileSize) {
            if (stopFlag?.get()) { return }
            int tw = Math.min( tileSize, x1 - tx )
            int th = Math.min( tileSize, y1 - ty )
            if (!maskShape.intersects( (double) tx, (double) ty, (double) tw, (double) th )) {
                onTileDone?.call( tileWeight )
                continue
            }

            def request = RegionRequest.createInstance( path, downsample, tx, ty, tw, th, z, t )
            def img = server.readRegion( request )
            def raster = img.getRaster()
            int iw = raster.getWidth()
            int ih = raster.getHeight()
            def maskRaster = BufferedImageTools.createShapeMask( iw, ih, maskShape, (double) tx, (double) ty, downsample ).getRaster()
            int nPix = iw * ih
            maskRaster.getSamples( 0, 0, iw, ih, 0, maskBuf )

            for (int c = 0; c < nCh; c++) {
                raster.getSamples( 0, 0, iw, ih, c, valBuf )
                long   cnt = count[ c ]
                double s   = sum[ c ]
                double sq  = sumSq[ c ]
                double mn  = minV[ c ]
                double mx  = maxV[ c ]
                long[] h   = intHist ? hist[ c ] : null
                for (int i = 0; i < nPix; i++) {
                    if (maskBuf[ i ] == 0) { continue }
                    double v = valBuf[ i ]
                    cnt++
                    s  += v
                    sq += v * v
                    if (v < mn) { mn = v }
                    if (v > mx) { mx = v }
                    if (h != null) {
                        int bin = (int) Math.round( v ) - histLo
                        if (bin < 0) { bin = 0 } else if (bin >= histSize) { bin = histSize - 1 }
                        h[ bin ]++
                    }
                }
                count[ c ] = cnt
                sum[ c ]   = s
                sumSq[ c ] = sq
                minV[ c ]  = mn
                maxV[ c ]  = mx
            }
            onTileDone?.call( tileWeight )
        }
    }

    // --- Pass 2 (floating-point median only): histogram over the discovered [min, max] range ---
    double[] binWidth = null
    if (wantMedian && !intHist) {
        int bins = 65536
        hist     = new long[ nCh ][ bins ]
        histSize = bins
        binWidth = new double[ nCh ]
        for (int c = 0; c < nCh; c++) {
            double range = maxV[ c ] - minV[ c ]
            binWidth[ c ] = range > 0 ? range / bins : 1.0d
        }
        for (int ty = y0; ty < y1; ty += tileSize) {
            for (int tx = x0; tx < x1; tx += tileSize) {
                if (stopFlag?.get()) { return }
                int tw = Math.min( tileSize, x1 - tx )
                int th = Math.min( tileSize, y1 - ty )
                if (!maskShape.intersects( (double) tx, (double) ty, (double) tw, (double) th )) {
                    onTileDone?.call( tileWeight )
                    continue
                }

                def request = RegionRequest.createInstance( path, downsample, tx, ty, tw, th, z, t )
                def img = server.readRegion( request )
                def raster = img.getRaster()
                int iw = raster.getWidth()
                int ih = raster.getHeight()
                def maskRaster = BufferedImageTools.createShapeMask( iw, ih, maskShape, (double) tx, (double) ty, downsample ).getRaster()
                int nPix = iw * ih
                maskRaster.getSamples( 0, 0, iw, ih, 0, maskBuf )
                for (int c = 0; c < nCh; c++) {
                    raster.getSamples( 0, 0, iw, ih, c, valBuf )
                    double lo = minV[ c ]
                    double bw = binWidth[ c ]
                    long[] h  = hist[ c ]
                    for (int i = 0; i < nPix; i++) {
                        if (maskBuf[ i ] == 0) { continue }
                        int bin = (int) ( (valBuf[ i ] - lo) / bw )
                        if (bin < 0) { bin = 0 } else if (bin >= bins) { bin = bins - 1 }
                        h[ bin ]++
                    }
                }
                onTileDone?.call( tileWeight )
            }
        }
    }

    // --- Write measurements, using the exact column names QuPath's native method produces ---
    def ml = anno.getMeasurementList()
    for (int c = 0; c < nCh; c++) {
        long n = count[ c ]
        if (n <= 0) { continue }
        String base = channelNames[ c ]
        if (wantMean) { ml.put( (base + ": Mean") as String, sum[ c ] / n ) }
        if (wantMin)  { ml.put( (base + ": Min")  as String, minV[ c ] ) }
        if (wantMax)  { ml.put( (base + ": Max")  as String, maxV[ c ] ) }
        if (wantStd || wantVar) {
            double variance = n > 1 ? Math.max( 0.0d, (sumSq[ c ] - (sum[ c ] * sum[ c ]) / n) / (n - 1) ) : 0.0d
            if (wantVar) { ml.put( (base + ": Variance") as String, variance ) }
            if (wantStd) { ml.put( (base + ": Std.Dev.") as String, Math.sqrt( variance ) ) }
        }
        if (wantMedian && hist != null) {
            // The middle value, or for an even count the mean of the two middle values, as the native
            // method gives. Ranks count from 1, and are the same rank when the count is odd
            long lowRank  = (n + 1L) / 2L
            long highRank = n / 2L + 1L
            int lowBin  = -1
            int highBin = histSize - 1
            long cum = 0
            for (int b = 0; b < histSize; b++) {
                cum += hist[ c ][ b ]
                if (lowBin < 0 && cum >= lowRank) { lowBin = b }
                if (cum >= highRank) { highBin = b; break }
            }
            if (lowBin < 0) { lowBin = highBin }
            def binValue = { int b ->
                if (intHist)               { return (double)(b + histLo) }
                if (maxV[ c ] > minV[ c ]) { return minV[ c ] + (b + 0.5d) * binWidth[ c ] }
                return minV[ c ]           // channel constant over the mask
            }
            ml.put( (base + ": Median") as String, (binValue( lowBin ) + binValue( highBin )) / 2.0d )
        }
    }
    ml.close()
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

// Every viewer showing an image redraws what it now holds — including split panes that aren't selected,
// which otherwise keep drawing the objects they had before the run. Done before saving: redrawing marks
// the data as having unsaved changes, which the save then clears
def redrawViewers = { imageData, List openViewers ->
    if (!openViewers) { return }
    runOnFxThreadAndWait {
        imageData.getHierarchy().fireHierarchyChangedEvent( this )
        openViewers.each { it.repaintEntireImage() }
    }
}
def projectDirectory = project.getBaseDirectory()

// Folder and file names of where preferences are stored in directory
def scriptPrefsFolderName = "AnnoTools-Data"
def scriptPrefsFileName   = "Add-Measurements_Preferences" + ".json"

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
    
    // Making the basic popup
    def dialog = new Dialog()
    dialog.setTitle( "What measurements should be added?" )
    
    def root = new BorderPane()
    root.setPrefHeight( 500 )
    
    dialog.getDialogPane().buttonTypes.setAll( ButtonType.OK, ButtonType.CANCEL )
    def okBtn = dialog.getDialogPane().lookupButton( ButtonType.OK )
    okBtn.setText( "Add Measurements" )
    ButtonBar.setButtonUniformSize( okBtn, false )
    
    
    
    // MIDDLE PANEL: Object pathclasses
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
    
    // Serial merge — directly into imageClassCounts and totalsByClass, no intermediate map needed
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
    pathClassBox.header.getChildren().addAll( pathClassBox.title, pathClassBox.spacer, pathClassBox.clear )    
    pathClassBox.CBs.each { it.setSelected( it.getUserData() in prePrefsMap.pathClasses ) }
    BorderPane.setMargin( pathClassBox.box, new Insets( 0, 15, 0, 0 ) )
    
    root.center = pathClassBox.box
    
    
    
    // LEFT PANEL: Image Selection
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
    
    root.left = imagesBox.box
    
    
    
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
            chosen && !chosen.any { imageClassCounts[ name ]?.get( it ) } ? ["No objects of the selected classes to measure"] : []
        }
    }
    (imagesBox.CBs + pathClassBox.CBs).each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal -> updateImageFlags() }
    }
    Platform.runLater { updateImageFlags() } // tick boxes are only built once the dialog shows
    
    
    
    // RIGHT PANEL: Intensity and Morphology measurements

    // The two panel headers are flagged with an underline instead of an outline, which sits too tight
    // around the words. The negative bottom inset drops the line clear of the text (it only moves where
    // the border is painted, not the layout)
    def underlineRedStyle = """
        -fx-border-color: red;
        -fx-border-width: 0 0 1 0;
        -fx-border-insets: 0 0 -4 0;
    """

    // INTENSITY MEASUREMENTS
    // Getting common available channels across project images
    def addIntensityMeasurementsCB = new CheckBox( "Intensity measurements" )
    setTT( addIntensityMeasurementsCB, "Include intensity measurements" )
    addIntensityMeasurementsCB.setSelected( prePrefsMap.addIntensityMeasurements == true )

    def serverGson = new Gson()
    def commonChannels = null
    
    project.getImageList().each { entry ->
        def summaryFile = entry.getEntryPath().resolve( "server.json" )
        if (Files.exists( summaryFile )) {
            def raw = new String( Files.readAllBytes( summaryFile ) )
            def server = serverGson.fromJson( raw, Object )
            // Compared as base names, so an image storing "DAPI (C1)" still matches one storing "DAPI"
            def channels = server?.metadata?.channels?.collect { baseChannelName( it.name as String ) } ?: []
            commonChannels = commonChannels == null ? channels : commonChannels.intersect( channels )
        }
    }
    commonChannels = commonChannels ?: []
    List channelsMap = commonChannels.collect { [name: it] }
    def intensityChannelsBox = makeCheckboxBox( "Channels", null, channelsMap, 180, 250 )
    intensityChannelsBox.header.getChildren().addAll( intensityChannelsBox.selectAll )
    // Base names on both sides, so selections saved before this (e.g. "DAPI (C1)") still restore
    def prePrefsChannels = ( prePrefsMap.intensityChannels ?: [] ).collect { baseChannelName( it as String ) }
    intensityChannelsBox.CBs.each { it.setSelected( it.getText() in prePrefsChannels ) }
    
    List intensityMeasurementsMap = ObjectMeasurements.Measurements.values().collect { [name: it.toString(), data: it] }
    def intensityMeasurementsBox = makeCheckboxBox( "Measurements",  "intensity measurements", intensityMeasurementsMap, 180, 200 )
    intensityMeasurementsBox.header.getChildren().addAll( intensityMeasurementsBox.selectAll )
    intensityMeasurementsBox.CBs.each { it.setSelected( it.getText() in prePrefsMap.intensityMeasurements ) }
    
    def intensityBoxes = new VBox( 10, intensityChannelsBox.box, intensityMeasurementsBox.box )
    def intensityPanel = new VBox( 10, addIntensityMeasurementsCB, intensityBoxes )
    
    intensityBoxes.disableProperty().bind(
        addIntensityMeasurementsCB.selectedProperty().not()
    )
    
    
    // MORPHOLOGY MEASUREMENTS
    def addMorphologyMeasurementsCB = new CheckBox( "Morphology measurements" )
    setTT( addMorphologyMeasurementsCB, "Include morphology measurements" )
    addMorphologyMeasurementsCB.setSelected( prePrefsMap.addMorphologyMeasurements == true )

    List morphologyMeasurementsMap = ObjectMeasurements.ShapeFeatures.values().collect { [name: it.toString(), data: it] }
    def morphologyMeasurementsBox = makeCheckboxBox( "Measurements", "morphology measurements", morphologyMeasurementsMap, 220 )
    morphologyMeasurementsBox.header.getChildren().addAll( morphologyMeasurementsBox.selectAll )
    morphologyMeasurementsBox.CBs.each { it.setSelected( it.getText() in prePrefsMap.morphologyMeasurementStrings ) }
    
    morphologyMeasurementsBox.box.disableProperty().bind(
        addMorphologyMeasurementsCB.selectedProperty().not()
    )
    
    def morphologyPanel = new VBox( 10, addMorphologyMeasurementsCB, morphologyMeasurementsBox.box )
    
    
    // Adding all together
    def allMeasurementsPanel = new HBox( 15, intensityPanel, morphologyPanel )
    root.right = allMeasurementsPanel
    
    
    
    // Disabling the "OK" button if appropriate inputs are not met
    def anySelectedImage = Bindings.createBooleanBinding(
        { imagesBox.CBs.any { it.isSelected() } },
        *imagesBox.CBs.collect { it.selectedProperty() }
    )
    def anySelectedClass = Bindings.createBooleanBinding(
        { pathClassBox.CBs.any { it.isSelected() } },
        *pathClassBox.CBs.collect { it.selectedProperty() }
    )
    def anySelectedChannel = Bindings.createBooleanBinding(
        { intensityChannelsBox.CBs.any { it.isSelected() } },
        *intensityChannelsBox.CBs.collect { it.selectedProperty() }
    )
    def anySelectedIntensityMeasurement = Bindings.createBooleanBinding(
        { intensityMeasurementsBox.CBs.any { it.isSelected() } },
        *intensityMeasurementsBox.CBs.collect { it.selectedProperty() }
    )
    def anySelectedMorphologyMeasurement = Bindings.createBooleanBinding(
        { morphologyMeasurementsBox.CBs.any { it.isSelected() } },
        *morphologyMeasurementsBox.CBs.collect { it.selectedProperty() }
    )
    
    def noMeasurementType = addIntensityMeasurementsCB.selectedProperty().not()
        .and( addMorphologyMeasurementsCB.selectedProperty().not() )

    okBtn.disableProperty().bind(
        anySelectedImage.not()
        .or( anySelectedClass.not() )
        .or( noMeasurementType )
        .or( addIntensityMeasurementsCB.selectedProperty()
            .and( anySelectedChannel.not()
                .or ( anySelectedIntensityMeasurement.not() ) ) )
        .or( addMorphologyMeasurementsCB.selectedProperty()
            .and( anySelectedMorphologyMeasurement.not() ) )
    )

    // Red outline on each input while it is a reason the "OK" button is disabled
    flagWhenBlocking( imagesBox.box, anySelectedImage.not() )
    flagWhenBlocking( pathClassBox.box, anySelectedClass.not() )
    flagWhenBlocking( addIntensityMeasurementsCB, noMeasurementType, underlineRedStyle )
    flagWhenBlocking( addMorphologyMeasurementsCB, noMeasurementType, underlineRedStyle )
    flagWhenBlocking( intensityChannelsBox.box,
        addIntensityMeasurementsCB.selectedProperty().and( anySelectedChannel.not() ) )
    flagWhenBlocking( intensityMeasurementsBox.box,
        addIntensityMeasurementsCB.selectedProperty().and( anySelectedIntensityMeasurement.not() ) )
    flagWhenBlocking( morphologyMeasurementsBox.box,
        addMorphologyMeasurementsCB.selectedProperty().and( anySelectedMorphologyMeasurement.not() ) )

    // Say why on hover, while disabled
    explainWhenDisabled( okBtn ) { [
        "Select at least one image"                                     : !anySelectedImage.get(),
        "Select at least one object class"                              : !anySelectedClass.get(),
        "Select \"Intensity measurements\" and/or \"Morphology measurements\"": noMeasurementType.get(),
        "Select at least one channel"                                   : addIntensityMeasurementsCB.isSelected() && !anySelectedChannel.get(),
        "Select at least one intensity measurement"                     : addIntensityMeasurementsCB.isSelected() && !anySelectedIntensityMeasurement.get(),
        "Select at least one morphology measurement"                    : addMorphologyMeasurementsCB.isSelected() && !anySelectedMorphologyMeasurement.get()
    ] }
    explainWhenDisabled( intensityBoxes ) { "Select \"Intensity measurements\" to use these" }
    explainWhenDisabled( morphologyMeasurementsBox.box ) { "Select \"Morphology measurements\" to use these" }

    
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
    def tickedIn = { box -> box.CBs.findAll { it.isSelected() }*.getText() }
    def upTo6    = { List names -> names.size() <= 6 ? listOut( names ) : names.take( 6 ).join( ", " ) + " and ${names.size() - 6} more" }

    addHelpButton( dialog, "Add measurements",
        "Adds intensity and shape measurements to every annotation of the classes you choose, in the images you choose.",
        [
            [ title: "Select images",
              nodes: [imagesBox.box],
              text : "The images to measure annotations in." +
                     "\nTop box: selects or clears them all. Current Image: selects the image open in the viewer." +
                     "\nShift-click: click one box, then shift-click another to set every box between them to match the first." +
                     "\nOutlined in red: a selected image with no annotations of the chosen classes, which will be skipped.",
              now  : { imagesNow( " and would be skipped" ) } ],
            [ title: "Object classes",
              nodes: [pathClassBox.box],
              text : "The classes of annotation to measure. The numbers show how many annotations of each class are in the selected images and in the project [images / project]." +
                     "\nAnnotations without a class are listed as Unclassified." +
                     "\nTrash button: clears the list.",
              now  : classesNow ],
            [ title: "Intensity measurements",
              nodes: [addIntensityMeasurementsCB],
              text : "Select to measure the intensity of the chosen channels within each annotation." +
                     "\nAnnotations are measured at full resolution, with very large ones measured in tiles so they don't run out of memory.",
              now  : {
                  if (!addIntensityMeasurementsCB.isSelected()) { return "Unselected, so no intensity measurements will be added." }
                  int channels = tickedIn( intensityChannelsBox ).size(), stats = tickedIn( intensityMeasurementsBox ).size()
                  List missing = [ channels ? null : "a channel", stats ? null : "a measurement" ].findAll()
                  if (missing) { return "Selected, but it still needs ${listOut( missing )}." }
                  String perChannel = channels == 1 ? "the 1 channel" : "each of the ${channels} channels"
                  "Selected. Each annotation will get ${some( channels * stats, 'intensity measurement', 'intensity measurements' )}: " +
                      "${some( stats, 'statistic', 'statistics' )} for ${perChannel}."
              } ],
            [ title: "Channels",
              nodes: [intensityChannelsBox.box],
              text : "The channels to measure. Only channels that every image in the project has are listed." +
                     "\nTop box: selects or clears them all.",
              now  : {
                  if (!addIntensityMeasurementsCB.isSelected()) { return "Not in use until Intensity measurements is selected." }
                  List names = tickedIn( intensityChannelsBox )
                  !intensityChannelsBox.CBs ? "No channels are shared by every image in the project, so there are none to measure." :
                  names ? "${upTo6( names )} selected." : "No channels selected yet."
              } ],
            [ title: "Measurements (intensity)",
              nodes: [intensityMeasurementsBox.box],
              text : "The statistics to work out for each selected channel: mean, median, minimum, maximum, standard deviation and variance." +
                     "\nEach is named the way QuPath names it, such as DAPI: Mean.",
              now  : {
                  if (!addIntensityMeasurementsCB.isSelected()) { return "Not in use until Intensity measurements is selected." }
                  List names = tickedIn( intensityMeasurementsBox )
                  names ? "${upTo6( names )} selected." : "No measurements selected yet."
              } ],
            [ title: "Morphology measurements",
              nodes: [addMorphologyMeasurementsCB, morphologyMeasurementsBox.box],
              oneBox: true,
              text : "Select to add shape measurements, then choose which in the list below." +
                     "\nQuPath's shape features include area, length, circularity, solidity, and maximum and minimum diameter.",
              now  : {
                  if (!addMorphologyMeasurementsCB.isSelected()) { return "Unselected, so no shape measurements will be added." }
                  int shapes = tickedIn( morphologyMeasurementsBox ).size()
                  shapes ? "Selected. Each annotation will get ${some( shapes, 'shape measurement', 'shape measurements' )}." :
                           "Selected, but no shape measurements are selected yet."
              } ],
            [ title: "Add Measurements",
              nodes: [okBtn],
              text : "Starts the run and remembers these choices for next time." +
                     "\nGreyed out until an image, a class and at least one kind of measurement (with what it needs) are chosen: hover over it to see what's missing.",
              now  : {
                  List images = chosenImages(), classes = chosenClasses()
                  boolean intensity = addIntensityMeasurementsCB.isSelected(), morphology = addMorphologyMeasurementsCB.isSelected()
                  List missing = [ images ? null : "an image",
                                   classes ? null : "an object class",
                                   intensity || morphology ? null : "a kind of measurement",
                                   intensity && !tickedIn( intensityChannelsBox ) ? "a channel" : null,
                                   intensity && !tickedIn( intensityMeasurementsBox ) ? "an intensity measurement" : null,
                                   morphology && !tickedIn( morphologyMeasurementsBox ) ? "a morphology measurement" : null ].findAll()
                  if (missing) { return "Not ready. Still needs ${listOut( missing )}." }
                  int annos = images.sum { annosIn( it, classes ) } as int
                  if (annos == 0) { return "None of the selected images has annotations of the selected classes, so there is nothing to measure." }
                  "Ready. It will add ${listOut( [intensity ? 'intensity' : null, morphology ? 'morphology' : null].findAll() )} measurements to " +
                      (annos == 1 ? "the 1 annotation" : "each of the ${annos} annotations") + " of the selected classes, in " +
                      "${some( images.count { annosIn( it, classes ) > 0 } as int, 'image', 'images' )}."
              } ]
        ] )

    // Attach root & show dialog
    // Widen boxes to their widest item (window grows to fit, up to the screen width)
    fitBoxesToScreen( dialog ) { [imagesBox, pathClassBox, intensityChannelsBox, intensityMeasurementsBox, morphologyMeasurementsBox] }

    dialog.getDialogPane().content = root
    
    def result = dialog.showAndWait()
    def button = result.get()
    
    if (button == ButtonType.OK) {                
        
        List<String>    selectedImages            = imagesBox.CBs.findAll { it.isSelected() }.collect { it.getText() }
        List<String>    selectedPathClasses       = pathClassBox.CBs.findAll { it.isSelected() }.collect { it.getUserData() }
        List            selectedChannels          = intensityChannelsBox.CBs.findAll { it.isSelected() }.collect { it.getText() }
        List            selectedIntensities       = intensityMeasurementsBox.CBs.findAll { it.isSelected() }.collect { it.getUserData() }
        ShapeFeatures[] selectedMorphologies      = morphologyMeasurementsBox.CBs.findAll { it.isSelected() }.collect { it.getUserData() }
        List<String>    selectedMorphologyStrings = morphologyMeasurementsBox.CBs.findAll { it.isSelected() }.collect { it.getText() }
        
        // Return all preferences if "OK" is selected
        return [
            selectedImages              : selectedImages,
            currentImage                : currentImageCB.isSelected(),
            pathClasses                 : selectedPathClasses,
            addIntensityMeasurements    : addIntensityMeasurementsCB.isSelected(),
            intensityChannels           : selectedChannels,
            intensityMeasurements       : selectedIntensities,
            addMorphologyMeasurements   : addMorphologyMeasurementsCB.isSelected(),
            morphologyMeasurements      : selectedMorphologies,
            morphologyMeasurementStrings: selectedMorphologyStrings
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

// --- PRE-FILTER: skip selected images with no viable objects of the chosen class(es) ---
// Images a viewer holds are judged from their live hierarchy, so unsaved objects count; the rest from
// their lightweight summary.json snapshot (read in parallel; an unreadable one keeps its image)
def keepImage = { counts -> codePreferences.pathClasses.any { (counts[ it ] ?: 0) > 0 } }
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
    logger.info( "[AnnoTools] Add measurements: skipping ${skippedNames.size()} selected image(s) with no viable objects: ${skippedNames.join( ', ' )}" )
}
if (imagesToRun.isEmpty()) {
    runOnFxThreadAndWait {
        def alert = new Alert( Alert.AlertType.INFORMATION )
        alert.setTitle( "Add measurements" )
        alert.setHeaderText( null )
        alert.setContentText( "None of the selected images contain viable objects of the chosen class(es).\nNothing to do." )
        def gui = QuPathGUI.getInstance()
        if (gui != null) { alert.initOwner( gui.getStage() ) }
        alert.showAndWait()
    }
    return
}
if (!skippedImages.isEmpty()) {
    Dialogs.showInfoNotification( "Add measurements", "Skipping ${skippedImages.size()} selected image(s) with no viable objects of the chosen class(es)." )
}

def total = imagesToRun.size()
def progressUI = createProgressWindow( "Processing", total, imagesToRun.collect { it.getImageName() } )
def stopNowFlag   = progressUI.stopNowFlag
def stopLaterFlag = progressUI.stopLaterFlag
int processed = 0
// Loading and saving count as steps of their own: on a big multi-channel image, opening it and writing
// the now much larger .qpdata back are the slowest part, so the window should say that's what it's doing
int netSteps = 1 +                                                     // loading
               (codePreferences.addIntensityMeasurements ? 1 : 0) +
               (codePreferences.addMorphologyMeasurements ? 1 : 0) +
               1                                                       // saving

// Fixed, machine-independent thresholds for the intensity step (hoisted so both the heap-aware
// concurrency sizing below and the per-image dispatch can see them). Keeping these constant keeps the
// measured values reproducible across machines; only the concurrency adapts to the hardware.
long nativeSampleBudget = 8_000_000L        // pixels x channels the native path may hold in memory
long tiledFullResBudget = 1_000_000_000L    // bbox pixels read at full res before the tiled path coarsens
// tileSize is sized to the channel count in the Tier-1 block below (a tile read pulls ALL channels, so
// on a high-plex image a 1024² tile is huge).

def pool = null

// Image being worked on, so the handler below can name it when a run dies — "stopped after
// an error" is useless when the run covered a hundred images
String imageInFlight = null

// Lets go of that image if a stop or an error leaves it part-way: it isn't saved, but a background copy is
// still closed, and the viewers showing live data redraw what it now holds
Closure releaseUnfinished = null

// Waits for one phase's tasks. False once "Stop now" is pressed — even if that came before any of them
// started, so none ran — as the image is then to be left part-way
def phaseFinished = { List futures ->
    try {
        for (def f in futures) {
            f.get()
            if (stopNowFlag.get()) { return false }
        }
    } catch (Exception e) {
        if (!stopNowFlag.get()) { throw e }
    }
    return !stopNowFlag.get()
}

try {
    // --- Tier 1: size intensity tile size AND concurrency to the heap AND the channel count ---
    // The dominant cost is reading tiles: extractChannels subsets AFTER the read, so every tile pulls
    // ALL of the image's channels. On a 38-channel UINT16 image a 1024² tile is ~80 MB, QuPath also
    // caches those tiles, and one task per core outran the garbage collector and exhausted the heap.
    // So: shrink the tile until one all-channels read is modest, and cap concurrency by channel count
    // so high-plex images run only a few masks (and reads) at once.
    Runtime rt   = Runtime.getRuntime()
    long maxHeap = rt.maxMemory()                        // the -Xmx ceiling that actually OOMs (not RAM)
    long usedNow = rt.totalMemory() - rt.freeMemory()    // live footprint right now (open image, UI, cache)
    long headroom        = Math.max( maxHeap - usedNow, maxHeap / 8 )   // floored so we never trust it to ~0
    long intensityBudget = (long)( headroom * 0.5d )     // leave half of headroom for QuPath's own churn

    // ALL of an image's channels are read per tile, so size for the most demanding image in the run, from
    // each one's server.json (falling back to the open image, then to defaults, where none can be read)
    int bytesPerSample = 0
    int imageChannels  = 0
    def serverJsonGson = new Gson()
    imagesToRun.each { entry ->
        try {
            def serverFile = entry.getEntryPath().resolve( "server.json" )
            if (!Files.exists( serverFile )) { return }
            def meta = serverJsonGson.fromJson( new String( Files.readAllBytes( serverFile ) ), Map )?.metadata
            imageChannels = Math.max( imageChannels, (meta?.channels?.size() ?: 0) as int )
            if (meta?.pixelType) { bytesPerSample = Math.max( bytesPerSample, PixelType.valueOf( meta.pixelType as String ).getBytesPerPixel() ) }
        } catch (Exception ignored) {}
    }
    bytesPerSample = bytesPerSample ?: (currentImageData?.getServer()?.getPixelType()?.getBytesPerPixel()) ?: 2
    imageChannels  = imageChannels  ?: (currentImageData?.getServer()?.nChannels()) ?: 1

    // Tile size: halve from 1024 until one all-channels tile read is within ~32 MB (floor 128).
    int tileSize = 1024
    while (tileSize > 128 && (long) tileSize * tileSize * imageChannels * bytesPerSample > 32L * 1024 * 1024) {
        tileSize >>= 1
    }

    // Concurrency: the smaller of what the heap allows (native per-task peak) and what keeps concurrent
    // multi-channel tile data bounded — the latter scales with channels, so high-plex images run fewer
    // masks in parallel (which also keeps the allocation rate under the GC's throughput).
    long nativePeak      = nativeSampleBudget * (bytesPerSample + 8L) * 2L
    long perMaskHotBytes = 16_000_000L * imageChannels * bytesPerSample   // ~16 MP of a mask "hot" at once
    int  byHeap          = (int) Math.max( 1L, intensityBudget / Math.max( 1L, nativePeak ) )
    int  byChannels      = (int) Math.max( 1L, intensityBudget / Math.max( 1L, perMaskHotBytes ) )
    int  intensityThreads = Math.max( 1, Math.min( rt.availableProcessors(), Math.min( byHeap, byChannels ) ) )

    pool = Executors.newFixedThreadPool( intensityThreads )
    progressUI.poolRef.set( pool )

    imageLoop:
    for (image in imagesToRun) {
        // Stopping when requested
        if (stopNowFlag.get() || stopLaterFlag.get()) { break }

        imageInFlight = image.getImageName()

        // Reporting current image
        updateProgressWindow( progressUI, [stepMax: netSteps, imageName: image.getImageName(), imageNum: processed+1,
                                           stepNum: 1, stepName: "Loading image", stepPer: 0] )
        
        // Editing the data a viewer already holds, so that pane stays in step with the run
        long loadStart = System.nanoTime()
        def openViewers = viewersForEntry( image )
        def imageData = openViewers ? openViewers[ 0 ].getImageData() : image.readImageData()
        long loadMs = (long)( ( System.nanoTime() - loadStart ) / 1_000_000L )
        releaseUnfinished = {
            redrawViewers( imageData, openViewers )
            if (!openViewers) { imageData.close() }
        }
        
        def server = imageData.getServer()
        def metadata = server.getMetadata()
        def channels = metadata.getChannels()

        // Filter annotations of interest
        List annotations = imageData.getHierarchy().getAnnotationObjects().findAll {
            classLabelForObject( it ) in codePreferences.pathClasses
        }
        
        int step = 2
        long intensityMs = 0
        long morphologyMs = 0
        
        // The dialog (and the saved preferences) hold BASE channel names, so map each selected one back
        // to this image's actual channel name before extracting — extractChannels matches names exactly,
        // and would not find "DAPI" in an image whose channel is stored as "DAPI (C1)".
        def channelNameByBase = [:]
        channels.each { ch -> channelNameByBase.putIfAbsent( baseChannelName( ch.getName() ), ch.getName() ) }
        List imageIntensityChannels = ( codePreferences.intensityChannels ?: [] )
            .collect { channelNameByBase[ baseChannelName( it as String ) ] }
            .findAll { it != null }
        
        // An image that has none of the selected channels is reported and skipped, rather than being
        // let through to fail the whole run inside extractChannels.
        boolean addIntensityHere = codePreferences.addIntensityMeasurements
        if (addIntensityHere && imageIntensityChannels.isEmpty()) {
            logger.warn( "[AnnoTools] Add measurements: '${imageInFlight}' has none of the selected channels " +
                         "(${codePreferences.intensityChannels.join( ', ' )}); skipping intensity measurements" )
            addIntensityHere = false
            step++
        }
        
        if (addIntensityHere) {
            
            updateProgressWindow( progressUI, [stepNum: step, stepName: "Adding intensity measurements", stepPer: 0] )
            long intensityStart = System.nanoTime()
            
            // Create server with only the desired channels to reduce processing time (otherwise will do all channels)
            def modifiedChannelServer = new TransformedServerBuilder( server )
                .extractChannels( *imageIntensityChannels )
                .build()

            // Unify the measurement column names across the project. QuPath names its columns from the
            // server's own channel metadata ("<channel>: Mean", ...), so an image storing "DAPI (C1)"
            // would write "DAPI (C1): Mean" while its neighbour writes "DAPI: Mean" — the same stain
            // landing in two different columns once the project is exported. Renaming the EXTRACTED
            // server's channels to the base names (the ones the dialog offered) makes every image write
            // the same column. Only this per-image wrapper is renamed; the project's own server keeps
            // its real channel names. Colours are carried over, and setMetadata only requires matching
            // pixel type and czt dimensions, which a rename leaves untouched.
            def extractedMetadata = modifiedChannelServer.getMetadata()
            def baseNamedChannels = extractedMetadata.getChannels().collect { ch ->
                ImageChannel.getInstance( baseChannelName( ch.getName() ), ch.getColor() )
            }
            if (baseNamedChannels != extractedMetadata.getChannels()) {
                modifiedChannelServer.setMetadata(
                    new ImageServerMetadata.Builder( extractedMetadata ).channels( baseNamedChannels ).build()
                )
            }

            // Channel names in the extracted server's band order — used to build measurement column
            // names ("<channel>: Mean", ...) that exactly match QuPath's native output. Read AFTER the
            // rename above, so the tiled path writes the same unified columns as the native one.
            List channelNames = modifiedChannelServer.getMetadata().getChannels().collect { it.getName() }
            int nSelectedChannels = Math.max( 1, channelNames.size() )

            // Each annotation is measured one of two ways, chosen by size (the nativeSampleBudget /
            // tiledFullResBudget / tileSize thresholds are set above the image loop):
            //  - Small/normal objects: QuPath's native addIntensityMeasurements at FULL resolution
            //    (downsample 1.0) — exact, and identical to what QuPath's GUI would produce.
            //  - Large masks (e.g. collagen per layer): a TILED full-resolution reader that streams the
            //    same statistics without holding the whole region — or every pixel value, as the native
            //    path does — in memory. This is what fixes the Java-heap-space crash. If a mask is so
            //    enormous that even tiled full-res would be too slow, it coarsens the read (downsample).
            //
            // Progress is weighted by WORK, not by object count, so one big tiled mask doesn't hold the
            // number still for as long as it takes to measure. Bounding-box samples is the same quantity
            // the native/tiled choice below is made on, so it is already a fair proxy for cost.
            def sampleLoadOf = { anno ->
                def r = anno.getROI()
                double a = r ? (r.getBoundsWidth() * r.getBoundsHeight()) : 0d
                Math.max( 1L, (long) a * nSelectedChannels )
            }
            long totalLoad = Math.max( 1L, (long) annotations.sum( 0L ) { sampleLoadOf( it ) } )
            def doneLoad = new AtomicLong( 0 )
            def lastPct  = new AtomicInteger( -1 )

            // Only posts to the FX thread when the whole-number percentage actually moves, so a mask
            // with thousands of tiles still costs at most 100 updates.
            def reportIntensity = { long units ->
                if (units <= 0) { return }
                long d = doneLoad.addAndGet( units )
                int pDone = (int) Math.min( 100L, (long)( d * 100L / totalLoad ) )
                int prev = lastPct.get()
                if (pDone > prev && lastPct.compareAndSet( prev, pDone )) {
                    updateProgressWindow( progressUI, [stepPer: pDone] )
                }
            }

            // Parallel processing the measurements
            def futures = annotations.collect { anno ->
                if (stopNowFlag.get()) { return null }
                try {
                    pool.submit({
                        def roi = anno.getROI()
                        double bboxArea = roi ? (roi.getBoundsWidth() * roi.getBoundsHeight()) : 0d
                        long sampleLoad = sampleLoadOf( anno )
                        if (sampleLoad <= nativeSampleBudget) {
                            // Small enough to measure whole, at full resolution, the native way
                            ObjectMeasurements.addIntensityMeasurements(
                                modifiedChannelServer,
                                anno,
                                1.0d,
                                codePreferences.intensityMeasurements,
                                []
                            )
                            reportIntensity( sampleLoad )
                        } else {
                            // Too big to hold in memory — tile it. Full res unless it's so large that
                            // even tiling would be too slow, in which case coarsen the read just enough.
                            double tiledDownsample = Math.max( 1.0d, Math.sqrt( bboxArea / tiledFullResBudget ) )
                            // Tile fractions are rounded down to whole units, so track what was
                            // actually reported and top up at the end — otherwise the step could
                            // never quite reach 100%.
                            def reportedHere = new AtomicLong( 0 )
                            addIntensityMeasurementsTiled(
                                modifiedChannelServer,
                                anno,
                                tiledDownsample,
                                codePreferences.intensityMeasurements,
                                channelNames,
                                tileSize,
                                stopNowFlag,
                                { double frac ->
                                    long units = (long)( sampleLoad * frac )
                                    reportedHere.addAndGet( units )
                                    reportIntensity( units )
                                }
                            )
                            reportIntensity( sampleLoad - reportedHere.get() )
                        }
                    } as Callable)
                } catch (RejectedExecutionException ignored) {
                    null
                }
            }.findAll { it != null }
        
            boolean intensityDone = phaseFinished( futures )
            
            intensityMs = (long)( ( System.nanoTime() - intensityStart ) / 1_000_000L )
            step++
            if (!intensityDone) { break } // "Stop now": the rest of this image's steps don't run
        }
        
        // Adding morphology measurements
        if (codePreferences.addMorphologyMeasurements) {
            
            updateProgressWindow( progressUI, [stepNum: step, stepName: "Adding morphology measurements", stepPer: 0] )
            
            long morphologyStart = System.nanoTime()
            def pixelCalibration = server.getPixelCalibration()
            ObjectMeasurements.addShapeMeasurements( annotations, pixelCalibration, codePreferences.morphologyMeasurements )
            morphologyMs = (long)( ( System.nanoTime() - morphologyStart ) / 1_000_000L )
            
            updateProgressWindow( progressUI, [stepName: "Adding morphology measurements", stepPer: 100] )
            
            step++
        }
        
        
        // Saving data — unless "Stop now" came part-way, which leaves the image as it was saved before
        if (stopNowFlag.get()) { break }
        updateProgressWindow( progressUI, [stepNum: step, stepName: "Saving image", stepPer: 0] )
        long saveStart = System.nanoTime()
        redrawViewers( imageData, openViewers )
        image.saveImageData( imageData )
        
        // Closing background imagedata — data a viewer holds stays open, it isn't ours to close
        if (!openViewers) {
            imageData.close()
        }
        releaseUnfinished = null
        
        long saveMs = (long)( ( System.nanoTime() - saveStart ) / 1_000_000L )
        updateProgressWindow( progressUI, [stepName: "Saving image", stepPer: 100] )
        
        // Every step, so a run that appears to hang can be pinned on a phase and an image.
        logger.info( "[AnnoTools] Add measurements: '${imageInFlight}' load ${loadMs} ms, " +
                     "intensity ${intensityMs} ms, morphology ${morphologyMs} ms, save ${saveMs} ms" )
        
        
        // - Reporting progress -
        processed++
        double frac = processed / total  
        updateProgressWindow( progressUI, [imagePer: frac] )
    }
} catch (Throwable err) {
    // Any failure (out-of-memory, I/O, a bad region read, ...) stops the run hard: signal in-flight
    // tiled reads to bail and kill the pool so it stops consuming memory. This runs outside the script
    // editor, so instead of rethrowing (which would be invisible) we log the error — full stack trace
    // to QuPath's log — and show a notification pointing there. The finally still closes the window.
    stopNowFlag.set( true )
    pool?.shutdownNow()
    String failedOn = imageInFlight ? " on image '${imageInFlight}'" : ""
    logger.error( "Add measurements stopped after an error" + failedOn, err )
    Dialogs.showErrorNotification( "Add measurements", "Stopped after an error" + failedOn + ". See the log for details." )
    return
} finally {
    // Always runs — on success, error, or user stop — so the modal progress window can never be left
    // open blocking the rest of QuPath.
    pool?.shutdown()
    progressUI.poolRef.set( null )
    releaseUnfinished?.call()
    Platform.runLater { progressUI.stage.close() }
}


import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javafx.util.Duration
import javafx.animation.*
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.*
import javafx.scene.Scene
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.layout.*
import javafx.scene.input.*
import javafx.stage.*
import javafx.beans.binding.Bindings
import com.google.gson.GsonBuilder
import com.google.gson.Gson

import qupath.lib.analysis.features.ObjectMeasurements
import qupath.lib.analysis.features.ObjectMeasurements.ShapeFeatures

import qupath.lib.images.servers.TransformedServerBuilder
import qupath.lib.images.servers.ImageChannel
import qupath.lib.images.servers.ImageServerMetadata
import qupath.lib.images.servers.PixelType
import qupath.lib.regions.RegionRequest
import qupath.lib.awt.common.BufferedImageTools
import qupath.lib.roi.GeometryTools

import qupath.lib.gui.tools.IconFactory
import org.controlsfx.glyphfont.FontAwesome


// Comments for tool launcher icon and tooltip
// ORDER: 5
// ICON: PLUS_CIRCLE
// Add intensity or morphology measurements to annotations