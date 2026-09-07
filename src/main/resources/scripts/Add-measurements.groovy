/*
 * Author: Alec McKendell
 * Date: 2026.2.4
 * Description: Add intensity or morphology measurements to annotations of interest.
*/


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
        
        def imageNum = new SimpleStringProperty( "0" )
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
def addIntensityMeasurementsTiled( server, anno, double downsample, Collection measurements,
                                   List channelNames, int tileSize, AtomicBoolean stopFlag ) {
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
    // memory. (QuPath's createROIMask builds the Area AND rebuilt it per tile — the original heap sink.)
    def maskShape = GeometryTools.geometryToShape( roi.getGeometry() )

    // Reusable per-tile buffers. This method runs on a single pooled thread per annotation, so these
    // are private to it — the parallelism is across annotations, not within one.
    int cap = tileSize * tileSize
    int[]    maskBuf = new int[ cap ]
    double[] valBuf  = new double[ cap ]

    // --- Pass 1: mean / std / min / max (+ integer-histogram median in the same pass) ---
    for (int ty = y0; ty < y1; ty += tileSize) {
        for (int tx = x0; tx < x1; tx += tileSize) {
            if (stopFlag?.get()) { return }
            int tw = Math.min( tileSize, x1 - tx )
            int th = Math.min( tileSize, y1 - ty )
            if (!maskShape.intersects( (double) tx, (double) ty, (double) tw, (double) th )) { continue }

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
                if (!maskShape.intersects( (double) tx, (double) ty, (double) tw, (double) th )) { continue }

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
            long target = (n + 1L) / 2L
            long cum = 0
            int medBin = histSize - 1
            for (int b = 0; b < histSize; b++) {
                cum += hist[ c ][ b ]
                if (cum >= target) { medBin = b; break }
            }
            double medVal
            if (intHist)                    { medVal = (double)(medBin + histLo) }
            else if (maxV[ c ] > minV[ c ]) { medVal = minV[ c ] + (medBin + 0.5d) * binWidth[ c ] }
            else                            { medVal = minV[ c ] }   // channel constant over the mask
            ml.put( (base + ": Median") as String, medVal )
        }
    }
    ml.close()
}


// Project & preferences file
def project = getProject()
def currentImageData = getCurrentImageData()
def currentEntry = currentImageData ? project.getEntry( currentImageData ) : null
def projectDirectory = project.getBaseDirectory()

// Folder and file names of where preferences are stored in directory
def scriptPrefsFolderName = "AnnoTools-Data"
def scriptPrefsFileName   = "AddingMeasurements_Preferences" + ".json"

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
    def viewer = getCurrentViewer()
    
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
        pathClassBox.CBs.each { cb ->
            def className = cb.getUserData()
            def sel   = selectedTotals[ className ] ?: 0
            def total = totalsByClass[ className ]  ?: 0
            cb.setText( total > 0 ? "${className} [${sel} / ${total}]" : className )
        }
    }
    
    imagesBox.CBs.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal -> updateClassCounts() }
    }
    updateClassCounts()
    
    
    
    // RIGHT PANEL: Intensity and Morphology measurements

    // The two panel headers are flagged with an underline instead of an outline, which sits too
    // tight around the words. Both states carry the same border, so its space is always reserved
    // and flagging can never change the height of the panel underneath. The negative bottom inset
    // drops the line clear of the text — it only moves where the border is painted, not the layout
    def clearUnderlineStyle = """
        -fx-border-color: transparent;
        -fx-border-width: 0 0 1 0;
        -fx-border-insets: 0 0 -4 0;
    """
    def underlineRedStyle = """
        -fx-border-color: red;
        -fx-border-width: 0 0 1 0;
        -fx-border-insets: 0 0 -4 0;
    """

    // INTENSITY MEASUREMENTS
    // Getting comomon available channels across project images
    def addIntensityMeasurementsCB = new CheckBox( "Intensity measurements" )
    setTT( addIntensityMeasurementsCB, "Include intensity measurements" )
    addIntensityMeasurementsCB.setSelected( prePrefsMap.addIntensityMeasurements == true )
    addIntensityMeasurementsCB.setStyle( clearUnderlineStyle )

    def serverGson = new Gson()
    def commonChannels = null
    
    project.getImageList().each { entry ->
        def summaryFile = entry.getEntryPath().resolve( "server.json" )
        if (Files.exists( summaryFile )) {
            def raw = new String( Files.readAllBytes( summaryFile ) )
            def server = serverGson.fromJson( raw, Object )
            def channels = server?.metadata?.channels?.collect { it.name } ?: []
            commonChannels = commonChannels == null ? channels : commonChannels.intersect( channels )
        }
    }
    commonChannels = commonChannels ?: []
    List channelsMap = commonChannels.collect { [name: it] }
    def intensityChannelsBox = makeCheckboxBox( "Channels", null, channelsMap, 180, 250 )
    intensityChannelsBox.header.getChildren().addAll( intensityChannelsBox.title, intensityChannelsBox.spacer, intensityChannelsBox.clear ) 
    intensityChannelsBox.CBs.each { it.setSelected( it.getText() in prePrefsMap.intensityChannels ) }
    
    List intensityMeasurementsMap = ObjectMeasurements.Measurements.values().collect { [name: it.toString(), data: it] }
    def intensityMeasurementsBox = makeCheckboxBox( "Measurements",  "intensity measurements", intensityMeasurementsMap, 180, 200 )
    intensityMeasurementsBox.header.getChildren().addAll( intensityMeasurementsBox.selectAll )
    intensityMeasurementsBox.CBs.each { it.setSelected( it.getText() in prePrefsMap.intensityMeasurements ) }
    
    def intensityBoxes = new VBox( 10, intensityChannelsBox.box, intensityMeasurementsBox.box )
    def intensityPanel = new VBox( 10, addIntensityMeasurementsCB, intensityBoxes )
    BorderPane.setMargin( imagesBox.box, new Insets( 0, 15, 0, 0 ) )
    
    intensityBoxes.disableProperty().bind(
        addIntensityMeasurementsCB.selectedProperty().not()
    )
    
    
    // MORPHOLOGY MEASUREMENTS
    def addMorphologyMeasurementsCB = new CheckBox( "Morphology measurements" )
    setTT( addMorphologyMeasurementsCB, "Include morphology measurements" )
    addMorphologyMeasurementsCB.setSelected( prePrefsMap.addMorphologyMeasurements == true )
    addMorphologyMeasurementsCB.setStyle( clearUnderlineStyle )

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

    
    // Attach root & show dialog
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
        
        // Return all preferences of "OK" is selected
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
    
} else { // Write to .json if exporting/importing
    
    def outputGson = new GsonBuilder().setPrettyPrinting().create()
    prefsFile.text = outputGson.toJson( codePreferences )
}


def imagesToRun = project.getImageList().findAll { it.getImageName() in codePreferences.selectedImages }

// --- PRE-FILTER: skip selected images with no viable objects of the chosen class(es) ---
// Non-current images are judged from the lightweight summary.json snapshot (parallel, fails open);
// the open image is judged from its LIVE hierarchy so unsaved objects count. Both use one predicate.
def keepImage = { counts -> codePreferences.pathClasses.any { (counts[ it ] ?: 0) > 0 } }
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
int netSteps = (codePreferences.addIntensityMeasurements ? 1 : 0) +
               (codePreferences.addMorphologyMeasurements ? 1 : 0)

// Fixed, machine-independent thresholds for the intensity step (hoisted so both the heap-aware
// concurrency sizing below and the per-image dispatch can see them). Keeping these constant keeps the
// measured values reproducible across machines; only the concurrency adapts to the hardware.
long nativeSampleBudget = 8_000_000L        // pixels x channels the native path may hold in memory
long tiledFullResBudget = 1_000_000_000L    // bbox pixels read at full res before the tiled path coarsens
// tileSize is sized to the image's channel count in the Tier-1 block below (a tile read pulls ALL
// channels, so on a high-plex image a 1024² tile is huge).

def pool = null

// Image being worked on, so the handler below can name it when a run dies — "stopped after
// an error" is useless when the run covered a hundred images
String imageInFlight = null

try {
    // --- Tier 1: size intensity tile size AND concurrency to the heap AND the channel count ---
    // The dominant cost is reading tiles: extractChannels subsets AFTER the read, so every tile pulls
    // ALL of the image's channels. On a 38-channel UINT16 image a 1024² tile is ~80 MB, QuPath also
    // caches those tiles, and one task per core drove the allocation rate past the GC — which is what
    // exhausted a 12 GB heap here. So: shrink the tile until one all-channels read is modest, and cap
    // concurrency by channel count so high-plex images run only a few masks (and reads) at once.
    Runtime rt   = Runtime.getRuntime()
    long maxHeap = rt.maxMemory()                        // the -Xmx ceiling that actually OOMs (not RAM)
    long usedNow = rt.totalMemory() - rt.freeMemory()    // live footprint right now (open image, UI, cache)
    long headroom        = Math.max( maxHeap - usedNow, maxHeap / 8 )   // floored so we never trust it to ~0
    long intensityBudget = (long)( headroom * 0.5d )     // leave half of headroom for QuPath's own churn
    int  bytesPerSample  = (currentImageData?.getServer()?.getPixelType()?.getBytesPerPixel()) ?: 2
    int  imageChannels   = (currentImageData?.getServer()?.nChannels()) ?: 1   // ALL are read per tile

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
        updateProgressWindow( progressUI, [stepMax: netSteps, imageName: image, imageNum: processed+1] )
        
        // If working on current image, modifying respective image data
        def imageData = image == currentEntry ? currentImageData : image.readImageData()
        
        def server = imageData.getServer()
        def metadata = server.getMetadata()
        def channels = metadata.getChannels()

        // Filter annotations of interest
        List annotations = imageData.getHierarchy().getAnnotationObjects().findAll {
            classLabelForObject( it ) in codePreferences.pathClasses
        }
        
        int step = 1
        
        if (codePreferences.addIntensityMeasurements) {
            
            updateProgressWindow( progressUI, [stepNum: step, stepName: "Adding intensity measurements"] )
            
            // Create server with only the desired channels to reduce processing time (otherwise will do all channels)
            def modifiedChannelServer = new TransformedServerBuilder( server )
                .extractChannels( *codePreferences.intensityChannels )
                .build()

            // Channel names in the extracted server's band order — used to build measurement column
            // names ("<channel>: Mean", ...) that exactly match QuPath's native output.
            List channelNames = modifiedChannelServer.getMetadata().getChannels().collect { it.getName() }
            int nSelectedChannels = Math.max( 1, channelNames.size() )

            // Each annotation is measured one of two ways, chosen by size (the nativeSampleBudget /
            // tiledFullResBudget / tileSize thresholds are hoisted above the image loop so the heap-aware
            // concurrency sizing can use them too):
            //  - Small/normal objects: QuPath's native addIntensityMeasurements at FULL resolution
            //    (downsample 1.0) — exact, and identical to what QuPath's GUI would produce.
            //  - Large masks (e.g. collagen per layer): a TILED full-resolution reader that streams the
            //    same statistics without holding the whole region — or every pixel value, as the native
            //    path does — in memory. This is what fixes the Java-heap-space crash. If a mask is so
            //    enormous that even tiled full-res would be too slow, it coarsens the read (downsample).
            def doneCount = new AtomicInteger( 0 )
            int numAnnos = annotations.size()
            int lastPct = -1

            // Parallel processing the measurements
            def futures = annotations.collect { anno ->
                if (stopNowFlag.get()) { return null }
                try {
                    pool.submit({
                        def roi = anno.getROI()
                        double bboxArea = roi ? (roi.getBoundsWidth() * roi.getBoundsHeight()) : 0d
                        long sampleLoad = (long) bboxArea * nSelectedChannels
                        if (sampleLoad <= nativeSampleBudget) {
                            // Small enough to measure whole, at full resolution, the native way
                            ObjectMeasurements.addIntensityMeasurements(
                                modifiedChannelServer,
                                anno,
                                1.0d,
                                codePreferences.intensityMeasurements,
                                []
                            )
                        } else {
                            // Too big to hold in memory — tile it. Full res unless it's so large that
                            // even tiling would be too slow, in which case coarsen the read just enough.
                            double tiledDownsample = Math.max( 1.0d, Math.sqrt( bboxArea / tiledFullResBudget ) )
                            addIntensityMeasurementsTiled(
                                modifiedChannelServer,
                                anno,
                                tiledDownsample,
                                codePreferences.intensityMeasurements,
                                channelNames,
                                tileSize,
                                stopNowFlag
                            )
                        }
                        int pDone = ( doneCount.incrementAndGet() / numAnnos * 100 ) as int
                        if (pDone != lastPct) {
                            updateProgressWindow( progressUI, [stepPer: pDone] )
                            lastPct = pDone
                        }
                    } as Callable)
                } catch (RejectedExecutionException ignored) {
                    null
                }
            }.findAll { it != null }
        
            try {
                for (def f in futures) {
                    f.get()
                    if (stopNowFlag.get()) {
                        pool.shutdownNow()
                        break
                    }
                }
            } catch (Exception e) {
                if (!stopNowFlag.get()) { throw e }
            }
            
            step++
        }
        
        // Adding morphology measurements
        if (codePreferences.addMorphologyMeasurements) {
            
            updateProgressWindow( progressUI, [stepNum: step, stepName: "Adding morphology measurements", stepPer: 0] )
            
            def pixelCalibration = server.getPixelCalibration()
            ObjectMeasurements.addShapeMeasurements( annotations, pixelCalibration, codePreferences.morphologyMeasurements )
            
            updateProgressWindow( progressUI, [stepName: "Adding morphology measurements", stepPer: 100] )
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
import qupath.lib.regions.RegionRequest
import qupath.lib.awt.common.BufferedImageTools
import qupath.lib.roi.GeometryTools

import qupath.lib.gui.tools.IconFactory
import org.controlsfx.glyphfont.FontAwesome


// Comments for tool launcher icon and tooltip
// ORDER: 5
// ICON: PLUS_CIRCLE
// Add intensity or morphology measurements to annotations