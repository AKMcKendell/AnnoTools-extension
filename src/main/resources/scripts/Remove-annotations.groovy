/*
 * Author: Alec McKendell
 * Date: 2026.6.8
 * Description: Script to remove objects by class.
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
// Function: Create window showing script progress
def createProgressWindow( String title, int totalImages, List imageNames = [] ) {
    return runOnFxThreadAndWait {
        def stage = new Stage()
        stage.setMinWidth( 350 )
        stage.setResizable( false )
        stage.setTitle( title )
        
        def imageNum = new SimpleStringProperty( "1" )
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
                stage.setWidth( preferred )
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
                    stage.setWidth(needed)
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
        def doneCount    = new AtomicInteger( 0 )
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
        checkboxBox.setMaxHeight( prefHeight )
        checkboxBox.setMinHeight( prefHeight )
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

    // Once shown, measure/lock against the widest possible count so later updates never get clipped
    selectAllCB.sceneProperty().addListener { obs, oldScene, newScene ->
        if (newScene != null) {
            String initialText = selectAllCB.getText()
            selectAllCB.setText( "${title.capitalize()} [${checkboxes.size()}]" )
            Platform.runLater {  // extra pulse so CSS skin is applied
                lockDimensions( selectAllCB )
                Platform.runLater { selectAllCB.setText( initialText ) }
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


// Project & preferences file
def project = getProject()
def currentImageData = getCurrentImageData()
def currentEntry = currentImageData ? project.getEntry( currentImageData ) : null
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
    
    // If an image is open, use its LIVE hierarchy for that image's class counts so unsaved
    // objects are reflected — both in the counts and in whether a class appears at all. Cheap,
    // since the image is already loaded, and safe on the FX thread we're already running on.
    if (currentEntry != null && currentImageData != null) {
        def liveCounts = [:]
        currentImageData.getHierarchy().getAnnotationObjects().each { obj ->
            def cn = obj.getPathClass()?.getName()
            if (cn != null) { liveCounts[ cn ] = (liveCounts[ cn ] ?: 0) + 1 }
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
    def anySelectedPathClass = Bindings.createBooleanBinding(
        { pathClassBox.CBs.any { it.isSelected() } },
        *pathClassBox.CBs.collect { it.selectedProperty() }
    )
    
    okBtn.disableProperty().bind(
        anySelectedImage.not()
        .or( anySelectedPathClass.not() )
    )
    
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
    
    // Attach root & show dialog
    dialog.getDialogPane().content = root
    
    def result = dialog.showAndWait()
    def button = result.get()
    
    if (confirmRemove) {                
        def selectedImages = imagesBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getText() }
        def selectedPathClasses = pathClassBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getUserData() }
        
        // Return all preferences of "OK" is selected
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
    
} else { // Write to .json if exporting/importing
    
    def outputGson = new GsonBuilder().setPrettyPrinting().create()
    prefsFile.text = outputGson.toJson( codePreferences )
}


def imagesToRun = project.getImageList().findAll { it.getImageName() in codePreferences.selectedImages }

// --- PRE-FILTER: skip selected images with no objects of the chosen class(es) to remove ---
// Non-current images are judged from the lightweight summary.json snapshot (parallel, fails open);
// the open image is judged from its LIVE hierarchy so unsaved objects count. Both use one predicate.
def keepImage = { counts -> codePreferences.objectClasses.any { (counts[ it ] ?: 0) > 0 } }
def keptNonCurrent = filterImagesBySummary( imagesToRun.findAll { it != currentEntry }, keepImage ).toRun as Set
boolean keepCurrent = false
if (currentEntry in imagesToRun) {
    def liveCounts = [:]
    currentImageData.getHierarchy().getAnnotationObjects().each { obj ->
        def cn = obj.getPathClass()?.getName()
        if (cn != null) { liveCounts[ cn ] = (liveCounts[ cn ] ?: 0) + 1 }
    }
    keepCurrent = keepImage( liveCounts )
}
def skippedImages = imagesToRun.findAll { img -> (img == currentEntry) ? !keepCurrent : !(img in keptNonCurrent) }
imagesToRun       = imagesToRun.findAll { img -> (img == currentEntry) ?  keepCurrent :  (img in keptNonCurrent) }

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

// Parallel running images
def futures = imagesToRun.findAll { it != currentEntry }.collect { image ->
    if (stopLaterFlag.get()) return null
    pool.submit({
        def imageData = image.readImageData()
        try {
            def hierarchy = imageData.getHierarchy()
            def objectsToRemove = hierarchy.getAnnotationObjects().findAll {
                it.getPathClass()?.getName() in codePreferences.objectClasses
            }
            hierarchy.removeObjects( objectsToRemove, false )
            image.saveImageData( imageData )
        } finally {
            imageData.close()
        }
        
        // Write atomic state — poll timeline reads these on its own schedule
        progressUI.lastImageName.set( image.getImageName() )
        progressUI.doneCount.incrementAndGet()
    } as Callable)
}.findAll { it != null }

futures.each { it.get() }
pool.shutdown()

// Current image handled serially after
if (!stopLaterFlag.get() && currentEntry in imagesToRun) {
    def hierarchy = currentImageData.getHierarchy()
    def objectsToRemove = hierarchy.getAnnotationObjects().findAll {
        it.getPathClass()?.getName() in codePreferences.objectClasses
    }
    hierarchy.removeObjects( objectsToRemove, false )
    currentEntry.saveImageData( currentImageData )
    runOnFxThreadAndWait {
        def viewer = getCurrentViewer()
        if (viewer != null) {
            currentImageData.getHierarchy().fireHierarchyChangedEvent( this )
            viewer.setImageData( currentImageData )
        }
    }
    progressUI.lastImageName.set( currentEntry.getImageName() )
    progressUI.doneCount.incrementAndGet()
}

// Brief pause so the poll timeline renders the completed state before close
Thread.sleep( 200 )
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