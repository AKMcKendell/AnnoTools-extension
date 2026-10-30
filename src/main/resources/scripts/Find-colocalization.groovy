/*
 * Author: Alec McKendell
 * Date: 2026.4.20
 * Description: Generate new annotations at the colocalization of others.
*/


// Class for nesting option strings
class NestStrings {
    static final String CLASS1     = "Under class 1 object"
    static final String TOP_OBJECT = "Under top object in hierarchy"
    static final String NONE       = "None"
}


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

// Function: Build a project-wide breakdown of images that already contain objects a run would
// affect. Counts are read cheaply from each image's summary.json (no image load); the open image
// is overridden with its live hierarchy so unsaved objects are reflected. classMatches( className )
// decides which class names are pre-existing objects. Returns imageName -> (className -> count),
// ordered by descending total, for affected images only.
def existingObjectsBreakdown( List images, Closure classMatches, Map liveDataByName ) {
    if (images.isEmpty()) { return [:] }

    def gson = new Gson()
    def nThreads = Math.max( 1, Math.min( Runtime.getRuntime().availableProcessors(), images.size() ) )
    def pool = Executors.newFixedThreadPool( nThreads )
    def rawList
    try {
        def futures = images.collect { entry ->
            pool.submit({
                def summaryFile = entry.getEntryPath().resolve( "summary.json" )
                def counts = [:]
                if (Files.exists( summaryFile )) {
                    try {
                        def raw = new String( Files.readAllBytes( summaryFile ) )
                        def summary = gson.fromJson( raw, Object )
                        def classCounts = summary?.get( "hierarchy" )?.get( "annotationClassificationCounts" )
                        if (classCounts instanceof Map) {
                            classCounts.each { k, v ->
                                def n = k.toString()
                                if (classMatches( n )) { counts[ n ] = (v instanceof Number) ? ((Number) v).intValue() : 0 }
                            }
                        }
                    } catch (Exception e) {
                        logger.warn( "[AnnoTools] existing-object scan: summary parse failed for ${entry.getImageName()}: ${e.getMessage()}" )
                    }
                }
                return [entry.getImageName(), counts]
            } as Callable)
        }
        rawList = futures.collect { it.get() }
    } finally {
        pool.shutdown()
    }

    def breakdown = new LinkedHashMap()
    rawList.each { pair ->
        def (imgName, counts) = pair
        if (!counts.isEmpty()) { breakdown[ imgName ] = counts }
    }

    // Override images a viewer holds with their live hierarchy so unsaved objects are reflected
    // accurately — every pane of a split viewer, not just the selected one
    liveDataByName.each { imgName, liveData ->
        if (!images.any { it.getImageName() == imgName }) { return }
        def live = runOnFxThreadAndWait {
            def m = new LinkedHashMap()
            liveData.getHierarchy().getAnnotationObjects().each { o ->
                def n = classLabelForObject( o )
                if (classMatches( n )) { m[ n ] = (m[ n ] ?: 0) + 1 }
            }
            return m
        }
        if (live.isEmpty()) { breakdown.remove( imgName ) } else { breakdown[ imgName ] = live }
    }

    // Order affected images by descending existing-object total, then image name
    return breakdown.sort { a, b ->
        int ta = (a.value.values().sum() ?: 0)
        int tb = (b.value.values().sum() ?: 0)
        (tb <=> ta) ?: naturalCompare( a.key, b.key )
    }
}

// Function: Show the collapsible existing-objects breakdown and return the run-wide choice:
// "keep" | "remove" | "skipDup" | "exit". objectNoun labels the objects (e.g. "colocalization").
def showExistingObjectsDialog( Map breakdown, String objectNoun ) {
    def exitBtn    = new ButtonType( "Exit", ButtonData.CANCEL_CLOSE )
    def keepBtn    = new ButtonType( "Keep", ButtonData.HELP )
    def removeBtn  = new ButtonType( "Remove", ButtonData.HELP )
    def skipDupBtn = new ButtonType( "Skip duplicates", ButtonData.HELP )

    int affectedImages  = breakdown.size()
    int affectedClasses = breakdown.values().collect { it.keySet() }.flatten().unique().size()
    int totalObjects    = (breakdown.values().collect { (it.values().sum() ?: 0) }.sum() ?: 0)

    def choice = runOnFxThreadAndWait {
        def alert = new Alert( Alert.AlertType.CONFIRMATION )
        alert.setTitle( "Existing objects found" )
        alert.setHeaderText(
            "Some images already contain ${objectNoun} objects from a previous run.\n" +
            "Choose how to handle every image that already contains them:\n" +
            "  • Keep — generate anyway, alongside the existing objects (may duplicate)\n" +
            "  • Remove — delete the existing objects, then generate\n" +
            "  • Skip duplicates — only process images without existing objects\n" +
            "WARNING: Removed objects and their children cannot be recovered."
        )
        alert.getDialogPane().buttonTypes.setAll( exitBtn, keepBtn, removeBtn, skipDupBtn )
        alert.getDialogPane().lookupButton( removeBtn )?.setDefaultButton( true )
        // Size each button to its own label rather than a uniform (widest-button) width
        [exitBtn, keepBtn, removeBtn, skipDupBtn].each { bt ->
            def n = alert.getDialogPane().lookupButton( bt )
            if (n != null) { ButtonBar.setButtonUniformSize( n, false ) }
        }

        // Collapsible root holds every affected image; each image expands to its existing classes
        def treeRoot = new TreeItem( "Images with existing objects [${affectedImages}]".toString() )
        treeRoot.setExpanded( false )
        breakdown.each { imgName, classes ->
            def imgItem = new TreeItem( "${imgName}  —  ${classes.size()} class(es)".toString() )
            imgItem.setExpanded( breakdown.size() == 1 )
            classes.sort { a, b -> (b.value <=> a.value) ?: naturalCompare( a.key, b.key ) }.each { cn, cnt ->
                imgItem.getChildren().add( new TreeItem( "${cn} [${cnt} existing]".toString() ) )
            }
            treeRoot.getChildren().add( imgItem )
        }
        def tree = new TreeView( treeRoot )
        tree.setShowRoot( true )

        // Size the tree to visible rows: compact when collapsed, growing as rows expand, capped so
        // many images scroll inside the tree rather than producing an oversized window.
        double rowHeight = 24.0
        int    maxRows   = 12
        tree.setFixedCellSize( rowHeight )
        tree.setPrefWidth( 480 )
        def sizeTreeToContent = {
            int rows  = Math.max( tree.getExpandedItemCount(), 1 )
            int shown = Math.min( rows, maxRows )
            double h  = shown * rowHeight + 6
            tree.setMinHeight( h )
            tree.setPrefHeight( h )
            tree.setMaxHeight( h )
        }
        sizeTreeToContent()
        tree.expandedItemCountProperty().addListener { obs, oldV, newV ->
            sizeTreeToContent()
            Platform.runLater {
                def w = tree.getScene()?.getWindow()
                if (w != null) { w.sizeToScene() }
            }
        }

        def summaryLabel = new Label( "${affectedImages} image(s) contain ${totalObjects} existing ${objectNoun} object(s) across ${affectedClasses} class(es):".toString() )
        summaryLabel.setStyle( "-fx-font-weight: bold;" )
        def content = new VBox( 8, summaryLabel, tree )
        content.setPadding( new Insets( 5 ) )
        alert.getDialogPane().setContent( content )
        alert.setResizable( true )

        return alert.showAndWait().orElse( exitBtn )
    }

    if (choice == keepBtn)    { return "keep" }
    if (choice == removeBtn)  { return "remove" }
    if (choice == skipDupBtn) { return "skipDup" }
    return "exit"
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

// Function: Final safety wrapper for ROI conversion. Uses buffer(0) only if the cleaned geometry is invalid
def cleanForROI( Geometry g ) {
    if (g == null || g.isEmpty()) {
        return g?.getFactory()?.createPolygon()
    }

    if (g instanceof Polygon || g instanceof MultiPolygon) {
        return g.isValid() ? g : g.buffer(0)
    }

    def polygonals = PolygonalExtracter.getPolygonals( g )
    if (polygonals == null || polygonals.isEmpty()) {
        return g.getFactory().createPolygon()
    }

    def result = polygonals.size() == 1 ? polygonals[ 0 ] : UnaryUnionOp.union( polygonals )

    // Fallback only if invalid
    if (result != null && !result.isValid()) {
        result = result.buffer( 0 )
    }

    return result
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


// Function: Walk up the hierarchy to an object's topmost ancestor — the annotation whose
// parent is the root object. Returns the object itself when it's already top-level. This is
// a read-only traversal, so it's safe to call from the parallel geometry phase.
def findTopAncestor( obj ) {
    def current = obj
    while (current?.getParent() != null && !current.getParent().isRootObject()) {
        current = current.getParent()
    }
    return current
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
def scriptPrefsFileName   = "Get-Colocalization_Preferences" + ".json"

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
    dialog.setTitle( "What colocalization objects should be generated?" )
    
    def root = new BorderPane()
    root.setPrefHeight( 350 )
    
    dialog.getDialogPane().buttonTypes.setAll( ButtonType.OK, ButtonType.CANCEL )
    def okBtn = dialog.getDialogPane().lookupButton( ButtonType.OK )
    okBtn.setText( "Make Objects" )
    ButtonBar.setButtonUniformSize( okBtn, false )
    
    
    // TOP ROW: Nesting dropdown
    def nestDropdownLabel = new Label( " Nest new objects?" )
    addIcon( nestDropdownLabel, FontAwesome.Glyph.SIGN_IN, 14 )
    nestDropdownLabel.setPadding( new Insets( 5 ) )
    
    def nestDropdown = new ComboBox()
    def nestOptions = [ NestStrings.CLASS1,
                        NestStrings.TOP_OBJECT,
                        NestStrings.NONE ]
    nestDropdown.getItems().addAll( nestOptions )
    setTT( [nestDropdownLabel, nestDropdown], "Where should new objects be nested?" )
    nestDropdown.setValue( prePrefsMap.nestBehavior in nestOptions ? prePrefsMap.nestBehavior : nestOptions[0] )
    
    def topSpacer = new Region()
    HBox.setHgrow( topSpacer, Priority.ALWAYS )
    
    def topRow = new HBox( 5 )
    topRow.setPadding( new Insets( 10 ) )
    topRow.getChildren().addAll( topSpacer, nestDropdownLabel, nestDropdown )
    
    root.top = topRow
    
    
    
    // BOTTOM PANEL: Output class preview
    def bottomSpacer = new Region()
    HBox.setHgrow( bottomSpacer, Priority.ALWAYS )
    def outputPreviewLabel = new Label()
    addIcon( outputPreviewLabel, FontAwesome.Glyph.EYE, 14 )
    outputPreviewLabel.setPadding( new Insets( 5 ) )
    setTT( outputPreviewLabel, "Preview of output class name" )
    def class1Prev = new SimpleStringProperty( "Class1" )
    def class2Prev = new SimpleStringProperty( "Class2" )
    outputPreviewLabel.textProperty().bind(
        Bindings.concat( " Output class preview: ", class1Prev, "-", class2Prev )
    )
    
    def bottomRow = new HBox( 5 )
    bottomRow.setPadding( new Insets( 10 ) )
    bottomRow.getChildren().addAll( bottomSpacer, outputPreviewLabel )
    
    root.bottom = bottomRow
    
    
    // CENTER PANELS: PathClass selection lists
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
    
    def pathClassBox1 = makeCheckboxBox( "Classes 1", "overlap class 1", pathClassCBsMap, 200 )
    pathClassBox1.header.getChildren().addAll( pathClassBox1.title, pathClassBox1.spacer, pathClassBox1.clear )    
    
    def pathClassBox2 = makeCheckboxBox( "Classes 2", "overlap class 2", pathClassCBsMap, 200 )    
    pathClassBox2.header.getChildren().addAll( pathClassBox2.title, pathClassBox2.spacer, pathClassBox2.clear )    
    
    // Adding listeners to stop same class from being selected twice and updating class preview
    pathClassBox1.CBs.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, selected ->
            def match = pathClassBox2.CBs.find { it.getUserData() == cb.getUserData() }
            match.setDisable( selected )
            
            def firstSelected = pathClassBox1.CBs.find { it.isSelected() }
            class1Prev.set( selected ? cb.getUserData() : firstSelected ? firstSelected.getUserData() : "Class1" )
        }
    } 
    pathClassBox2.CBs.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, selected ->
            def match = pathClassBox1.CBs.find { it.getUserData() == cb.getUserData() }
            match.setDisable( selected )
            
            def firstSelected = pathClassBox2.CBs.find { it.isSelected() }
            class2Prev.set( selected ? cb.getUserData() : firstSelected ? firstSelected.getUserData() : "Class2" )
        }
    }
    
    // Pre-selecting classes
    pathClassBox1.CBs.each { it.setSelected( it.getUserData() in prePrefsMap.objectClasses1 ) }
    pathClassBox2.CBs.each { it.setSelected( it.getUserData() in prePrefsMap.objectClasses2 ) }
    
    def pathclassBoxes = new HBox( 10, pathClassBox1.box, pathClassBox2.box )
    root.center = pathclassBoxes
    
    
    
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
        [pathClassBox1, pathClassBox2].each { pcBox ->
            pcBox.CBs.each { cb ->
                def className = cb.getUserData()
                def sel   = selectedTotals[ className ] ?: 0
                def total = totalsByClass[ className ]  ?: 0
                cb.setText( total > 0 ? "${className} [${sel} / ${total}]" : className )
                // Reserve room for the widest the count can read, so selecting images doesn't resize the box
                cb.getProperties().put( "widestText", { font -> total > 0 ? "${className} [${widestNumber( total, font )} / ${total}]" : className } )
            }
        }
    }
    
    imagesBox.CBs.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal -> updateClassCounts() }
    }
    updateClassCounts()

    // Red ring on each selected image without objects of the classes chosen in either box (only once a
    // box has a class chosen; until then the box itself is flagged)
    def updateImageFlags = {
        def chosen1 = pathClassBox1.CBs.findAll { it.isSelected() }*.getUserData()
        def chosen2 = pathClassBox2.CBs.findAll { it.isSelected() }*.getUserData()
        outlineImageProblems( imagesBox.CBs, currentEntry?.getImageName() ) { String name ->
            def lacks = { List chosen -> chosen && !chosen.any { imageClassCounts[ name ]?.get( it ) } }
            [lacks( chosen1 ) ? "No objects of the classes chosen in Classes 1" : null,
             lacks( chosen2 ) ? "No objects of the classes chosen in Classes 2" : null].findAll()
        }
    }
    (imagesBox.CBs + pathClassBox1.CBs + pathClassBox2.CBs).each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal -> updateImageFlags() }
    }
    Platform.runLater { updateImageFlags() } // tick boxes are only built once the dialog shows
    
    
    
    // Disabling the "OK" button if not all inputs are met    
    def anySelectedImage = Bindings.createBooleanBinding(
        { imagesBox.CBs.any { it.isSelected() } },
        *imagesBox.CBs.collect { it.selectedProperty() }
    )
    def anySelectedClass1 = Bindings.createBooleanBinding(
        { pathClassBox1.CBs.any { it.isSelected() } },
        *pathClassBox1.CBs.collect { it.selectedProperty() }
    )
    def anySelectedClass2 = Bindings.createBooleanBinding(
        { pathClassBox2.CBs.any { it.isSelected() } },
        *pathClassBox2.CBs.collect { it.selectedProperty() }
    )
    
    okBtn.disableProperty().bind(
        anySelectedImage.not()
        .or( anySelectedClass1.not() )
        .or( anySelectedClass2.not() )
    )

    // Red outline on each input while it is a reason the "OK" button is disabled
    flagWhenBlocking( imagesBox.box, anySelectedImage.not() )
    flagWhenBlocking( pathClassBox1.box, anySelectedClass1.not() )
    flagWhenBlocking( pathClassBox2.box, anySelectedClass2.not() )

    // Say why on hover, while disabled
    explainWhenDisabled( okBtn ) { [
        "Select at least one image"            : !anySelectedImage.get(),
        "Select at least one class in Classes 1": !anySelectedClass1.get(),
        "Select at least one class in Classes 2": !anySelectedClass2.get()
    ] }
    pathClassBox1.CBs.each { explainWhenDisabled( it ) { "Already selected in Classes 2" } }
    pathClassBox2.CBs.each { explainWhenDisabled( it ) { "Already selected in Classes 1" } }

    
    // Attach root & show dialog
    // Widen boxes to their widest item (window grows to fit, up to the screen width)
    fitBoxesToScreen( dialog ) { [imagesBox, pathClassBox1, pathClassBox2] }

    dialog.getDialogPane().content = root
    
    def result = dialog.showAndWait()
    def button = result.get()
    
    if (button == ButtonType.OK) {                
        def selectedImages = imagesBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getText() }
                
        List<String> selectedClasses1 = pathClassBox1.CBs.findAll { it.isSelected() }.collect { it.getUserData() }
        List<String> selectedClasses2 = pathClassBox2.CBs.findAll { it.isSelected() }.collect { it.getUserData() }
        
        // Return all preferences if "OK" is selected
        return [
            selectedImages : selectedImages,
            currentImage   : currentImageCB.isSelected(),
            objectClasses1 : selectedClasses1,
            objectClasses2 : selectedClasses2,
            nestBehavior   : nestDropdown.getValue()
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

// --- PRE-FILTER: skip selected images that can't produce any colocalization ---
// An image is worth loading only if it has at least one class-1 AND one class-2 object (needed to
// overlap), OR an existing "class1-class2" output object a re-run would clear.
def coLocOutputs = [] as Set
codePreferences.objectClasses1.each { c1 -> codePreferences.objectClasses2.each { c2 -> coLocOutputs << (c1 + "-" + c2) } }
def keepImage = { counts ->
    boolean has1   = codePreferences.objectClasses1.any { (counts[ it ] ?: 0) > 0 }
    boolean has2   = codePreferences.objectClasses2.any { (counts[ it ] ?: 0) > 0 }
    boolean hasOut = coLocOutputs.any { (counts[ it ] ?: 0) > 0 }
    (has1 && has2) || hasOut
}
// Images a viewer holds are judged from their live hierarchy, so unsaved objects count; the rest from
// their lightweight summary.json snapshot (read in parallel; an unreadable one keeps its image)
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
    logger.info( "[AnnoTools] Find colocalization: skipping ${skippedNames.size()} selected image(s) lacking both classes: ${skippedNames.join( ', ' )}" )
}
if (imagesToRun.isEmpty()) {
    runOnFxThreadAndWait {
        def alert = new Alert( Alert.AlertType.INFORMATION )
        alert.setTitle( "Find colocalization" )
        alert.setHeaderText( null )
        alert.setContentText( "None of the selected images contain both chosen class groups.\nNothing to do." )
        def gui = QuPathGUI.getInstance()
        if (gui != null) { alert.initOwner( gui.getStage() ) }
        alert.showAndWait()
    }
    return
}
if (!skippedImages.isEmpty()) {
    Dialogs.showInfoNotification( "Find colocalization", "Skipping ${skippedImages.size()} selected image(s) lacking both chosen class groups." )
}

// If any selected image already contains colocalization objects from a previous run, ask once — up
// front — how to handle them across the whole run. The breakdown is read cheaply from summary.json.
def colocNames = codePreferences.objectClasses1.collectMany { c1 ->
    codePreferences.objectClasses2.collect { c2 -> (c1 + "-" + c2).toString() }
} as Set
def isExistingColocClass = { String n -> n != null && colocNames.contains( n ) }

// Images a viewer holds are read from their live hierarchy so unsaved objects are counted
def liveDataByName = [:]
imagesToRun.each { img ->
    def liveData = liveDataForEntry( img )
    if (liveData != null) { liveDataByName[ img.getImageName() ] = liveData }
}
def existingBreakdown = existingObjectsBreakdown( imagesToRun, isExistingColocClass, liveDataByName )
String existingObjsAction = "remove"   // no pre-existing objects => proceed as before
if (!existingBreakdown.isEmpty()) {
    existingObjsAction = showExistingObjectsDialog( existingBreakdown, "colocalization" )
    if (existingObjsAction == "exit") { return }
}

def total = imagesToRun.size()
def progressUI = createProgressWindow( "Processing", total, imagesToRun.collect { it.getImageName() } )
def stopNowFlag = progressUI.stopNowFlag
def stopLaterFlag = progressUI.stopLaterFlag
int processed = 0

// Loading and saving count as steps of their own: with few objects they're most of the iteration, so
// the window should say that's what it's doing. The count is the same for every image.
int netSteps = 1 +                                                                         // loading
               codePreferences.objectClasses1.size() * codePreferences.objectClasses2.size() +
               1                                                                           // saving

// Pool for parallel processing
def pool = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() )
progressUI.poolRef.set( pool )

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
imageLoop:
for (image in imagesToRun) {
    // Stopping when requested
    if (stopNowFlag.get() || stopLaterFlag.get()) { break }

    imageInFlight = image.getImageName()

    // Reporting current image
    updateProgressWindow( progressUI, [stepMax: netSteps, imageName: image.getImageName(), imageNum: processed+1,
                                       stepNum: 1, stepName: "Loading image", stepPer: 0] )
    
    // Editing the data a viewer already holds, so that pane stays in step with the run
    def openViewers = viewersForEntry( image )
    def imageData = openViewers ? openViewers[ 0 ].getImageData() : image.readImageData()
    def hierarchy = imageData.getHierarchy()
    def annotations = hierarchy.getAnnotationObjects()
    def annosByClass = annotations.groupBy { classLabelForObject( it ) }

    // Apply the up-front existing-objects choice for this image.
    boolean removeExisting = (existingObjsAction == "remove")
    if (existingObjsAction == "skipDup") {
        // Skip images that already contain colocalization objects; process the rest normally.
        boolean hasExistingColoc = annotations.any { isExistingColocClass( classLabelForObject( it ) ) }
        if (hasExistingColoc) {
            if (!openViewers) { imageData.close() }
            processed++
            updateProgressWindow( progressUI, [imagePer: processed / total] )
            continue imageLoop
        }
    }
    releaseUnfinished = {
        redrawViewers( imageData, openViewers )
        if (!openViewers) { imageData.close() }
    }

    int step = 2
    
    // --- EXECUTION ---
    class1Loop:
    for (class1 in codePreferences.objectClasses1) {
        
        class2Loop:
        for (class2 in codePreferences.objectClasses2) {
            if (stopNowFlag.get()) { continue imageLoop } // "Stop now": no new work on this image
            
            String newClass = class1 + "-" + class2 
            updateProgressWindow( progressUI, [stepNum: step, stepName: "Working on ${newClass}", stepPer: 0] )
            
            // Adding the new class to the pathclass list if it is not already on it.
            // getAvailablePathClasses() backs QuPath's class pane, so the mutation
            // must run on the FX application thread — checked there too, so two
            // images queuing the same class can't add it twice.
            def availableClasses = getQuPath().getAvailablePathClasses()
            def newPathClass = getPathClass( newClass )
            Platform.runLater { if (!availableClasses.contains( newPathClass )) { availableClasses << newPathClass } }
       
            // Clearing objects if they already exist to not double add
            if (removeExisting) {
                def oldObjects = annotations.findAll {
                    classLabelForObject( it ) == newClass
                }
                // removeObjects fires a hierarchy event (and drops removed objects from the selection)
                // that QuPath's panes react to, so on data a viewer holds it must run on the FX thread
                if (openViewers) { runOnFxThreadAndWait { hierarchy.removeObjects( oldObjects, false ) } }
                else             { hierarchy.removeObjects( oldObjects, false ) }
            }
            
            // Setting the color of the new class: the average of the two, channel by channel
            int color1 = getPathClass( class1 ).getColor()
            int color2 = getPathClass( class2 ).getColor()
            def mix = { Closure channel -> (channel( color1 ) + channel( color2 )).intdiv( 2 ) as int }
            getPathClass( newClass ).setColor( ColorTools.packRGB( mix( ColorTools.&red ), mix( ColorTools.&green ), mix( ColorTools.&blue ) ) )
            
                
            // Getting objects from each class
            def class1Annos = annosByClass[ class1 ] ?: []
            def class2Annos = annosByClass[ class2 ] ?: []

            // Skipping the pair if either class has no objects (still counting its step)
            if (class1Annos.isEmpty() || class2Annos.isEmpty()) {
                step++
                continue
            }

            // Build index + cache geometries once before the loop.
            // Cleaning is parallelized since it can involve costly validation/buffering.
            int numClass2 = class2Annos.size()
            def class2GeomCache = new Object[ numClass2 ]
            def cacheFutures = (0..<numClass2).collect { i ->
                if (stopNowFlag.get()) { return null }
                try {
                    pool.submit({
                        class2GeomCache[ i ] = cleanForROI( class2Annos[ i ].getROI().getGeometry() )
                    } as Callable)
                } catch (RejectedExecutionException ignored) {
                    null
                }
            }.findAll { it != null }
            if (!phaseFinished( cacheFutures )) { continue imageLoop }

            def class2Tree = new STRtree()
            // Enveloping the annos to reduce comparisons
            // by avoiding ones that are geometrically impossible
            class2Annos.eachWithIndex { anno, i ->
                def geom = class2GeomCache[i]
                if (geom != null && !geom.isEmpty()) {
                    class2Tree.insert( geom.getEnvelopeInternal(), i )
                }
            }

            int numClass1 = class1Annos.size()
            def overlapResults = new Object[ numClass1 ]
            def doneCount = new AtomicInteger( 0 )
            int lastPct = -1

            // Parallel geometry phase
            def futures = (0..<numClass1).collect { idx ->
                if (stopNowFlag.get()) { return null }
                try {
                    pool.submit({
                        def class1Anno = class1Annos[ idx ]
                        def class1Geom = cleanForROI( class1Anno.getROI().getGeometry() )
                        def parent = (codePreferences.nestBehavior == NestStrings.CLASS1)     ? class1Anno :
                                     (codePreferences.nestBehavior == NestStrings.TOP_OBJECT) ? findTopAncestor( class1Anno ) :
                                                                                                 hierarchy.getRootObject()
                        def localResults = []
                        if (class1Geom != null && !class1Geom.isEmpty()) {
                            class2Tree.query( class1Geom.getEnvelopeInternal() ).each { i ->
                                def class2Geom = class2GeomCache[ i ]
                                if (class2Geom != null && !class2Geom.isEmpty() && class1Geom.intersects( class2Geom )) {
                                    try {
                                        def overlapGeom = cleanForROI( class1Geom.intersection( class2Geom ) )
                                        if (overlapGeom != null && !overlapGeom.isEmpty() && overlapGeom.getArea() > 1) {
                                            localResults << [ geom: overlapGeom, parent: parent, plane: class1Anno.getROI().getImagePlane() ]
                                        }
                                    } catch (Exception e) {
                                        logger.warn( "Skipping invalid intersection between ${class1Anno.getName()} and ${class2Annos[i]?.getName()}: ${e.getMessage()}" )
                                    }
                                }
                            }
                        }
                        overlapResults[ idx ] = localResults
            
                        int pDone = ( doneCount.incrementAndGet() / numClass1 * 100 ) as int
                        if (pDone != lastPct) {
                            updateProgressWindow( progressUI, [ stepPer: pDone ] )
                            lastPct = pDone
                        }
                    } as Callable)
                } catch (RejectedExecutionException ignored) {
                    null
                }
            }.findAll { it != null }
            
            // Allowing live cancel
            if (!phaseFinished( futures )) { continue imageLoop }
            
            // Serial hierarchy adds
            runOnFxThreadAndWait {
                overlapResults.each { localResults ->
                    localResults?.each { r ->
                        def overlapROI = GeometryTools.geometryToROI( r.geom, r.plane )
                        def overlapObj = PathObjects.createAnnotationObject( overlapROI, getPathClass( newClass ) )
                        hierarchy.addObjectBelowParent( r.parent, overlapObj, false )
                        overlapObj.setLocked( true )
                    }
                }
            }
            step ++
        }
    }
    
    
    // Saving data — unless "Stop now" came part-way, which leaves the image as it was saved before
    if (stopNowFlag.get()) { continue imageLoop }
    updateProgressWindow( progressUI, [stepNum: netSteps, stepName: "Saving image", stepPer: 0] )
    redrawViewers( imageData, openViewers )
    image.saveImageData( imageData )
    
    // Closing background imagedata — data a viewer holds stays open, it isn't ours to close
    if (!openViewers) {
        imageData.close()
    }
    releaseUnfinished = null
    updateProgressWindow( progressUI, [stepName: "Saving image", stepPer: 100] )
    
    
    // - Reporting progress -
    processed++
    double frac = processed / total 
    updateProgressWindow( progressUI, [imagePer: frac] )
}
} catch (Throwable err) {
    // Runs outside the script editor, so a thrown error would be invisible: kill the pool, log it (the
    // full stack trace goes to QuPath's log) and point the user there
    pool.shutdownNow()
    String failedOn = imageInFlight ? " on image '${imageInFlight}'" : ""
    logger.error( "Find colocalization stopped after an error" + failedOn, err )
    Dialogs.showErrorNotification( "Find colocalization", "Stopped after an error" + failedOn + ". See the log for details." )
} finally {
    // Always runs — on success, error, or user stop — so the modal progress window can never be left
    // open blocking the rest of QuPath
    pool.shutdown()
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

import org.locationtech.jts.geom.*
import org.locationtech.jts.geom.util.PolygonalExtracter
import org.locationtech.jts.operation.union.UnaryUnionOp

import qupath.lib.common.ColorTools
import qupath.lib.gui.tools.IconFactory
import org.controlsfx.glyphfont.FontAwesome
import org.locationtech.jts.index.strtree.STRtree


// Comments for tool launcher icon and tooltip
// ORDER: 3
// ICON: COPY
// Make new annotations at the intersection of others