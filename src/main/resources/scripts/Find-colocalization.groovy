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
// Function: Helper to run on FX thread
def runOnFxThreadAndWait( Closure fxCode ) {
    def latch = new CountDownLatch( 1 )
    def result = [value: null]
    Platform.runLater {
        try {
            result.value = fxCode.call()
        } finally {
            latch.countDown()
        }
    }
    latch.await()
    return result.value
}

// Function: Build a project-wide breakdown of images that already contain objects a run would
// affect. Counts are read cheaply from each image's summary.json (no image load); the open image
// is overridden with its live hierarchy so unsaved objects are reflected. classMatches( className )
// decides which class names are pre-existing objects. Returns imageName -> (className -> count),
// ordered by descending total, for affected images only.
def existingObjectsBreakdown( List images, Closure classMatches, currentEntry, currentImageData ) {
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

    // Override the open image with its live hierarchy so unsaved objects are reflected accurately
    if (currentEntry != null && currentImageData != null && images.any { it.getImageName() == currentEntry.getImageName() }) {
        def curName = currentEntry.getImageName()
        def live = runOnFxThreadAndWait {
            def m = new LinkedHashMap()
            currentImageData.getHierarchy().getAnnotationObjects().each { o ->
                def n = classLabelForObject( o )
                if (classMatches( n )) { m[ n ] = (m[ n ] ?: 0) + 1 }
            }
            return m
        }
        if (live.isEmpty()) { breakdown.remove( curName ) } else { breakdown[ curName ] = live }
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
                stage.setWidth( preferred )
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
                    stage.setMinWidth( needed )
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
        if (baselineWidth > stage.getMinWidth()) { stage.setMinWidth( baselineWidth ) }
        if (baselineWidth > stage.getWidth())    { stage.setWidth( baselineWidth ) }
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
        
        // Stop both timelines when the stage is actually closed
        stage.setOnHidden { timeline.stop() }
        
        // Signal to cancel script
        def stopNowFlag   = new AtomicBoolean( false )
        def stopLaterFlag = new AtomicBoolean( false )
        
        def stopNowLabel = new Label( "Stopping after operation..." )
        stopNowLabel.setPadding( new Insets( 4 ) )
        def stopLaterLabel = new Label( "Stopping after image..." )
        stopLaterLabel.setPadding( new Insets( 4 ) )
        
        def poolRef = new AtomicReference( null )

        def showStopConfirmation = {
            // Preventing a double show
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
    
    // Select / deselect all action
    selectAllCB.setOnAction {
        selectAllCB.setIndeterminate( false )
        groupCBs.each { it.setSelected( selectAllCB.isSelected() ) }
    }
    
    // Update relevant labels/instructions when boxes are clicked
    groupCBs.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal ->
            int numCBs = groupCBs.size()
            int numSelected = groupCBs.count { it.isSelected() }
            
            if (numSelected !in [0, numCBs]) {
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

    // Hold the binding on the node — a derived binding (e.g. someBinding.not()) is only weakly
    // referenced by its dependencies, so otherwise it can be collected part way through the dialog
    node.getProperties().put( "flagWhenBlocking", blocking )

    def applyStyle = { boolean isBlocking -> node.setStyle( isBlocking ? flaggedStyle : clearedStyle ) }
    blocking.addListener { obs, oldVal, newVal -> applyStyle( newVal ) }
    applyStyle( blocking.getValue() )
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
    
    
    // Making scrollpane slide back to left if horizontally scrollable
    Platform.runLater {
        def hBar = scrollPane.lookupAll( ".scroll-bar" )
            .find { it instanceof ScrollBar && it.orientation == Orientation.HORIZONTAL }
            
        if (hBar.isVisible()) {
            def snapBack = new PauseTransition( Duration.millis( 300 ) )
            snapBack.setOnFinished {
                new Timeline(
                    new KeyFrame(
                        Duration.millis( 200 ),
                        new KeyValue( scrollPane.hvalueProperty(), 0, Interpolator.EASE_BOTH )
                    )
                ).play()
            }
            
            scrollPane.addEventFilter( ScrollEvent.SCROLL ) { e ->
                if (Math.abs( e.deltaX ) > 0 || e.isShiftDown()) {
                    def delta = (e.deltaX != 0 ? e.deltaX : e.deltaY)
                    def sensitivity = 250.0
                    def newValue = scrollPane.hvalue - (delta / sensitivity)
                    scrollPane.hvalue = Math.max( 0, Math.min( 1, newValue ) )
                    e.consume()
                }
            }
            
            scrollPane.addEventFilter( ScrollEvent.SCROLL_FINISHED ) { e ->
                snapBack.playFromStart()
                e.consume()
            }
            
            hBar.addEventFilter( MouseEvent.MOUSE_RELEASED ) {
                snapBack.playFromStart()
            }
        }
    }
    
    
    def titleLabel = new Label()
    titleLabel.setPadding( new Insets( 5, 0, 0, 0 ) )

    // Set initial wording so the box's purpose is clear even when there's nothing to check
    titleLabel.setText( "${title.capitalize()} [0]" )
    selectAllCB.setText( "${title.capitalize()} [0]" )

    // Reserve at least the widest-count width so the count changing (e.g. [9] -> [10]) never nudges the
    // header — but only as a *floor* (minWidth), never a fixed or max width. Capping the width to a
    // measurement is exactly what clipped the title: the first box is laid out before the app's font is
    // applied, so its measured width comes out too small, and a hard cap then truncates the label for
    // good (later boxes, rebuilt after the window is styled, measured fine — hence "[0]" clipped but a
    // rebuilt "[1]" didn't). With only a floor the checkbox always takes at least its own preferred width
    // for the current text, so the title can never be clipped no matter when we happen to measure.
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

    // Add listeners to checkboxes (for changing instructions/labels)
    def refreshLabels = {
            
            String TTword = keyWord ?: title
            
            def selectedNow = checkboxes.findAll { it.isSelected() }.collect { it.getText() }
            titleLabel.setText( "${title.capitalize()} [${selectedNow.size()}]" )
            
            selectAllCB.setText( "${title.capitalize()} [${selectedNow.size()}]" )
            setTT( selectAllCB, selectAllCB.isSelected() ? "Deselect all ${TTword.uncapitalize()}" : "Select all ${TTword.uncapitalize()}" )
            
            String tooltipText = "Select ${TTword.uncapitalize()}"
            
            if (selectedNow.size() > 0) {
                tooltipText = "${(keyWord ?: title).capitalize()} selected:"
                selectedNow.eachWithIndex { item, i ->
                    tooltipText += "\n ${i+1}. ${item}"
                }
            }
            
            setTT( [titleLabel, scrollPane], tooltipText )
        }
    checkboxes.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal -> refreshLabels() }
        cb.textProperty().addListener { obs, oldVal, newVal -> refreshLabels() }
        
        // Double fire to spark listeners
        cb.setSelected( !cb.isSelected() )
        cb.setSelected( !cb.isSelected() )
    }
    
    // Allowing shift + click to select/deselect multiple
    CheckBox lastClicked = null   
    checkboxes.eachWithIndex { cb, idx ->
    
        cb.addEventHandler( MouseEvent.MOUSE_CLICKED ) { e ->
    
            if (e.isShiftDown() && lastClicked != null) {
                int lastIdx = checkboxes.indexOf( lastClicked )
                int start = Math.min( lastIdx, idx )
                int end   = Math.max( lastIdx, idx )
    
                // Use the state of the checkbox just clicked
                boolean value = lastClicked.isSelected()
    
                (start..end).each { i ->
                    cb = checkboxes[i]
                    if (!cb.isDisabled() && cb.isVisible() ) {
                        cb.setSelected( value )
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
    
    return [
        box      : checkboxBox,
        header   : topRow,
        selectAll: selectAllCB,
        title    : titleLabel,
        spacer   : topRowSpacer,
        clear    : clearBtn,
        CBs      : checkboxes
    ]
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
// summary (DefaultProject.HierarchySummary.pathClassToString), which labels null-class
// (unclassified) objects "Unclassified". That's the label the class list offers, so null-class
// objects must map back to it here — otherwise "Unclassified" is selectable but never matches.
def classLabelForObject( obj ) {
    return obj.getPathClass()?.getName() ?: "Unclassified"
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
    
    
    // TOP ROW: Nesting checkbox
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
    
    
    // RIGHT PANELS: PathClass selection list
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
    
    // If an image is open, use its LIVE hierarchy for that image's class counts so unsaved
    // objects are reflected — both in the counts and in whether a class appears at all. Cheap,
    // since the image is already loaded, and safe on the FX thread we're already running on.
    if (currentEntry != null && currentImageData != null) {
        def liveCounts = [:]
        currentImageData.getHierarchy().getAnnotationObjects().each { obj ->
            def cn = classLabelForObject( obj )
            liveCounts[ cn ] = (liveCounts[ cn ] ?: 0) + 1
        }
        imageClassCounts[ currentEntry.getImageName() ] = liveCounts

        // Rebuild totals in place so the override is reflected everywhere downstream
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
        
        // Setting bright border on checkbox for current image 
        Platform.runLater { 
            [currentCB, currentImageCB].each {
                it.lookup( ".box" )?.setStyle("""
                    -fx-border-color: black;
                    -fx-border-width: 2;
                    -fx-border-radius: 1;
                """)
            }
        }
        
        // If current image was only one selected, will update to new image
        if (prePrefsMap.currentImage == true && prePrefsMap.selectedImages.size() == 1) {
            imagesBox.CBs.each { it.setSelected( false ) }
            currentImageCB.setSelected( true )
        }
    } else {
        currentImageCB.setDisable( true )
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
            }
        }
    }
    
    imagesBox.CBs.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal -> updateClassCounts() }
    }
    updateClassCounts()
    
    
    
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

    
    // Attach root & show dialog
    dialog.getDialogPane().content = root
    
    def result = dialog.showAndWait()
    def button = result.get()
    
    if (button == ButtonType.OK) {                
        def selectedImages = imagesBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getText() }
                
        List<String> selectedClasses1 = pathClassBox1.CBs.findAll { it.isSelected() }.collect { it.getUserData() }
        List<String> selectedClasses2 = pathClassBox2.CBs.findAll { it.isSelected() }.collect { it.getUserData() }
        
        // Return all preferences of "OK" is selected
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
    
} else { // Write to .json if exporting/importing
    
    def outputGson = new GsonBuilder().setPrettyPrinting().create()
    prefsFile.text = outputGson.toJson( codePreferences )
}


def imagesToRun = project.getImageList().findAll { it.getImageName() in codePreferences.selectedImages }

// --- PRE-FILTER: skip selected images that can't produce any colocalization ---
// An image is worth loading only if it has at least one class-1 AND one class-2 object (needed to
// overlap), OR an existing "class1-class2" output object a re-run would clear. Non-current images
// are judged from the summary.json snapshot (parallel, fails open); the open image from its LIVE
// hierarchy so unsaved objects count. Both use one predicate.
def coLocOutputs = [] as Set
codePreferences.objectClasses1.each { c1 -> codePreferences.objectClasses2.each { c2 -> coLocOutputs << (c1 + "-" + c2) } }
def keepImage = { counts ->
    boolean has1   = codePreferences.objectClasses1.any { (counts[ it ] ?: 0) > 0 }
    boolean has2   = codePreferences.objectClasses2.any { (counts[ it ] ?: 0) > 0 }
    boolean hasOut = coLocOutputs.any { (counts[ it ] ?: 0) > 0 }
    (has1 && has2) || hasOut
}
def keptNonCurrent = filterImagesBySummary( imagesToRun.findAll { it != currentEntry }, keepImage ).toRun as Set
boolean keepCurrent = false
if (currentEntry in imagesToRun) {
    def liveCounts = [:]
    currentImageData.getHierarchy().getAnnotationObjects().each { obj ->
        def cn = classLabelForObject( obj )
        liveCounts[ cn ] = (liveCounts[ cn ] ?: 0) + 1
    }
    keepCurrent = keepImage( liveCounts )
}
def skippedImages = imagesToRun.findAll { img -> (img == currentEntry) ? !keepCurrent : !(img in keptNonCurrent) }
imagesToRun       = imagesToRun.findAll { img -> (img == currentEntry) ?  keepCurrent :  (img in keptNonCurrent) }

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

def existingBreakdown = existingObjectsBreakdown( imagesToRun, isExistingColocClass, currentEntry, currentImageData )
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
int netSteps = codePreferences.objectClasses1.size() * codePreferences.objectClasses2.size()

// Pool for parallel processing
def pool = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() )
progressUI.poolRef.set( pool )

// Image being worked on, so the handler below can name it when a run dies — "stopped after
// an error" is useless when the run covered a hundred images
String imageInFlight = null

try {
imageLoop:
for (image in imagesToRun) {
    // Stopping when requested
    if (stopNowFlag.get() || stopLaterFlag.get()) { break }

    imageInFlight = image.getImageName()

    // Reporting current image
    updateProgressWindow( progressUI, [stepMax: netSteps, imageName: image, imageNum: processed+1] )
    
    // If working on current image, modifying respective image data
    def imageData = image == currentEntry ? currentImageData : image.readImageData()
    def hierarchy = imageData.getHierarchy()
    def annotations = hierarchy.getAnnotationObjects()
    def annosByClass = annotations.groupBy { classLabelForObject( it ) }

    // Apply the up-front existing-objects choice for this image.
    boolean removeExisting = (existingObjsAction == "remove")
    if (existingObjsAction == "skipDup") {
        // Skip images that already contain colocalization objects; process the rest normally.
        boolean hasExistingColoc = annotations.any { isExistingColocClass( it.getPathClass()?.getName() ) }
        if (hasExistingColoc) {
            if (image != currentEntry) { imageData.close() }
            processed++
            updateProgressWindow( progressUI, [imagePer: processed / total] )
            continue imageLoop
        }
    }

    int step = 1
    
    // --- EXECUTION ---
    class1Loop:
    for (class1 in codePreferences.objectClasses1) {
        
        class2Loop:
        for (class2 in codePreferences.objectClasses2) {
            
            String newClass = class1 + "-" + class2 
            updateProgressWindow( progressUI, [stepNum: step, stepName: "Working on ${newClass}"] )
            
            // Adding the new class to the pathclass list if it is not already on it.
            // getAvailablePathClasses() backs QuPath's class pane, so the mutation
            // must run on the FX application thread.
            def availableClasses = getQuPath().getAvailablePathClasses()
            if (!availableClasses.contains( getPathClass( newClass )) ) {
                Platform.runLater { availableClasses << getPathClass( newClass ) }
            }
       
            // Clearing objects if they already exist to not double add
            if (removeExisting) {
                def oldObjects = annotations.findAll {
                    it.getPathClass()?.getName() == newClass
                }
                hierarchy.removeObjects( oldObjects, false )
            }
            
            //Setting the color of the new class
            int color1 = getPathClass( class1 ).getColor()
            int color2 = getPathClass( class2 ).getColor()
            int newClassColor = ((color1 + color2) / 2)
            getPathClass( newClass ).setColor( newClassColor )
            
                
            // Getting objects from each class
            def class1Annos = annosByClass[ class1 ] ?: []
            def class2Annos = annosByClass[ class2 ] ?: []



            // Skipping the loop if either class has no objects
            if (class1Annos.isEmpty() || class2Annos.isEmpty()) { continue }

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
            try {
                for (def f in cacheFutures) {
                    f.get()
                    if (stopNowFlag.get()) { continue imageLoop }
                }
            } catch (Exception e) {
                if (stopNowFlag.get()) { continue imageLoop }
                throw e
            }

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
            try {
                for (def f in futures) {
                    f.get()
                    if (stopNowFlag.get()) {
                        pool.shutdownNow()
                        continue imageLoop
                    }
                }
            } catch (Exception e) {
                if (stopNowFlag.get()) {
                    continue imageLoop
                }
                throw e
            }            
            
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
    
    
    // Saving data
    image.saveImageData( imageData )
    
    // Ensure current viewer updates its annotation list when working on the current image
    if (image == currentEntry) {
        runOnFxThreadAndWait {
            def viewer = getCurrentViewer()
            if (viewer != null) {
                // Re-assign the same ImageData to force a UI refresh
                imageData.getHierarchy().fireHierarchyChangedEvent( this )
                viewer.setImageData( imageData )
            }
        }
    }
    
    // Closing background imagedata for non-current image
    if (image != currentEntry) {
        imageData.close()
    }
    
    
    // - Reporting progress -
    processed++
    double frac = processed / total 
    updateProgressWindow( progressUI, [imagePer: frac] )
}

pool.shutdown()
} catch (Throwable err) {
    // Runs outside the script editor, so a thrown error would be invisible: kill the pool, log it (the
    // full stack trace goes to QuPath's log), point the user there, and close the modal window so QuPath
    // isn't left blocked. Then stop.
    pool.shutdownNow()
    String failedOn = imageInFlight ? " on image '${imageInFlight}'" : ""
    logger.error( "Find colocalization stopped after an error" + failedOn, err )
    Dialogs.showErrorNotification( "Find colocalization", "Stopped after an error" + failedOn + ". See the log for details." )
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

import qupath.lib.gui.tools.IconFactory
import org.controlsfx.glyphfont.FontAwesome
import org.locationtech.jts.index.strtree.STRtree


// Comments for tool launcher icon and tooltip
// ORDER: 3
// ICON: COPY
// Make new annotations at the intersection of others