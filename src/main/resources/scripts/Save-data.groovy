/*
 * Author: Alec McKendell
 * Date: 2026.6.10
 * Description: Exports annotation details based on selections with nesting levels included.
*/


def omittedMdKeys = ["Parent_ID", "Parent_Name"]

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
def createProgressWindow( String title, int totalImages, boolean simplify = false, Stage ownerStage = null ) {
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
        
        if (simplify) {
            rows.removeAll( imageRow, bottomRow )
        }
        
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
        stage.initOwner( ownerStage ?: gui?.getStage() )
        stage.initModality( Modality.WINDOW_MODAL )
        stage.show()
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
        def lastImageName = new AtomicReference<String>( "Starting..." )
        
        // Poll at 100 ms — faster than the render pulse, so no burst is missed
        def pollTimeline = new Timeline(
            new KeyFrame(
                javafx.util.Duration.millis( 100 ),
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

// Function: Make a checkbox that selects/deselects all checkboxes
def makeSelectAllCB( List groupCBs ) {
    def selectAllCB = new CheckBox()
    
    // Select / deselect all action
    selectAllCB.setOnAction {
        selectAllCB.setIndeterminate( false )
        groupCBs.each { it.setSelected( it.isVisible() ? selectAllCB.isSelected() : it.isSelected() ) }
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


// Function: Make a box with checkboxes and a modifiable header
def makeCheckboxBox( String title, String keyWord = null, List objects, int prefWidth = 0, int prefHeight = 0 ) {
    
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
    
    checkboxes.each { checkboxContainer.getChildren().add( it ) }
    
    def selectAllCB = makeSelectAllCB( checkboxes )
    
    def scrollPane = new ScrollPane( checkboxContainer )
    VBox.setVgrow( scrollPane, Priority.ALWAYS )
    
    
    // Making scrollpane slide back to left if horizontally scrollable
    scrollPane.sceneProperty().addListener { obs, oldScene, newScene ->
        if (newScene != null) {
            Platform.runLater {  // extra pulse so CSS skin is applied
                def hBar = scrollPane.lookupAll( ".scroll-bar" )
                    .find { it instanceof ScrollBar && it.orientation == Orientation.HORIZONTAL }
    
                if (hBar != null && hBar.isVisible()) {
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
        }
    }
    
    
    def titleLabel = new Label()
    titleLabel.setPadding( new Insets( 5, 0, 0, 0 ) )
    
    String TTword = keyWord ?: title
    selectAllCB.setText( "${title.capitalize()} [0]" )
    setTT( selectAllCB, "Include all ${TTword.uncapitalize()}" )
    
    // Add listeners to checkboxes (for changing instructions/labels)
    checkboxes.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal ->            
            
            def selectedNow = checkboxes.findAll { it.isSelected() && it.isVisible() }.collect { it.getText() }
            titleLabel.setText( "${title.capitalize()} [${selectedNow.size()}]" )
            
            selectAllCB.setText( "${title.capitalize()} [${selectedNow.size()}]" )
            
            String tooltipText = "Select ${TTword.uncapitalize()}"
            
            if (selectedNow.size() > 0) {
                tooltipText = "${(keyWord ?: title).capitalize()} selected:"
                selectedNow.eachWithIndex { item, i ->
                    tooltipText += "\n ${i+1}. ${item}"
                }
            }
            
            setTT( [titleLabel, scrollPane], tooltipText )
        }
        
        cb.visibleProperty().addListener { obs, oldVal, newVal ->
            cb.setSelected( !cb.isSelected() )
            cb.setSelected( !cb.isSelected() )
        }
        
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

// Function: Create icon for UI
def addIcon( node, iconCode, int size ) {
    def icon = IconFactory.createNode( iconCode, size )
    node.setGraphic( icon )
}

// Function: Scan the entire project to collect annotation field availability by class
def collectProjectAnnotationIndex( project, Stage ownerStage = null, Collection omittedMdKeys = [] ) {
    List projectImages = project.getImageList()
    def collectingUI = createProgressWindow( "Getting project specifics", projectImages.size(), true, ownerStage )
    int total = projectImages.size()
    
    def nThreads = Math.min( Runtime.getRuntime().availableProcessors(), total )
    def pool = Executors.newFixedThreadPool( nThreads )
    
    def futures = projectImages.collect { entry ->
        pool.submit({
            def localClassCounts     = [:].withDefault { 0 }
            def localMeasurements    = [:].withDefault { new LinkedHashSet() }
            def localMetadata        = [:].withDefault { new LinkedHashSet() }

            def imageData = null
            try {
                imageData = entry.readImageData()
                if (imageData) {
                    def annos = imageData.getHierarchy()?.getAnnotationObjects() ?: []
                    annos.each { obj ->
                        def className = obj.getPathClass()?.getName()
                        if (className) {
                            localClassCounts[ className ]++
                            obj.getMeasurementList()?.getMeasurementNames()?.each { localMeasurements[ className ] << it }
                            obj.getMetadata()?.keySet()?.each { 
                                if (!(it in omittedMdKeys)) { localMetadata[ className ] << it }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try { imageData?.close() } catch (Exception ignored2) {}
            }

            collectingUI.lastImageName.set( entry.getImageName() )
            collectingUI.doneCount.incrementAndGet()

            return [ classCounts: localClassCounts, measurements: localMeasurements, metadata: localMetadata ]
        } as Callable)
    }

    // Serial merge — fast, no contention
    def index = [
        classCounts        : [:].withDefault { 0 },
        measurementsByClass: [:].withDefault { new LinkedHashSet() },
        metadataByClass    : [:].withDefault { new LinkedHashSet() }
    ]
    futures.each { future ->
        def local = future.get()
        local.classCounts.each    { k, v -> index.classCounts[ k ] = ( index.classCounts[ k ] ?: 0 ) + v }
        local.measurements.each   { k, v -> index.measurementsByClass[ k ].addAll( v ) }
        local.metadata.each       { k, v -> index.metadataByClass[ k ].addAll( v ) }
    }

    pool.shutdown()

    Thread.sleep( 200 )
    Platform.runLater { collectingUI.stage.close() }

    index.measurementsByClass = index.measurementsByClass.collectEntries { k, v -> [k, new LinkedHashSet( v )] }
    index.metadataByClass     = index.metadataByClass.collectEntries     { k, v -> [k, new LinkedHashSet( v )] }
    index.classCounts         = index.classCounts.collectEntries         { k, v -> [k, v as int] }
    return index
}



// Project & preferences file
def currentImageData = getCurrentImageData()
def currentEntry = currentImageData ? project.getEntry( currentImageData ) : null
def projectDirectory = project.getBaseDirectory()

// Folder and file names of where preferences are stored in directory
def scriptPrefsFolderName = "AnnoTools-Data"
def scriptPrefsFileName   = "Save-Anno-Data_Preferences" + ".json"
def fieldCacheFileName    = "ProjectFieldCache" + ".json"

def prefsFolder = new File( projectDirectory, scriptPrefsFolderName )
if (!prefsFolder.exists()) { prefsFolder.mkdirs() }

def prefsFile = new File( prefsFolder, scriptPrefsFileName )
if (!prefsFile.exists()) { prefsFile.createNewFile() }

def fieldCacheFile = new File( prefsFolder, fieldCacheFileName )

// Read previous preferences
def prePrefsMap = [:]
def inputGson = new Gson()
def outputGson = new GsonBuilder().setPrettyPrinting().create()
if (prefsFile.length() > 0L && prefsFile.getText() != "null") {
    def readDataFile = inputGson.fromJson( prefsFile.getText(), Object )
    if (readDataFile instanceof Map) {
        prePrefsMap = readDataFile
    }
}

// Build map of project specifics to cache
def projectFingerprint = { proj ->
    def imageNames = proj.getImageList().collect { it.getImageName() }.sort()
    def baseDir = proj.getBaseDirectory()?.getAbsolutePath() ?: ""
    return [ baseDir: baseDir,
             images:  imageNames ]
}

// If chache with project fingerprint exists, gets measurement/metadata specifics from there
// If not, regenerates by scanning the project
def loadProjectCache = { proj, File cacheFile, boolean forceReload = false, Stage ownerStage = null ->
    def currentFingerprint = projectFingerprint( proj )
    if (cacheFile.exists() && cacheFile.length() > 0L && !forceReload) {
        try {
            def cached = inputGson.fromJson( cacheFile.text, Object )
            if (cached?.fingerprint == currentFingerprint && cached?.index instanceof Map) {
                return cached
            }
        } catch (Exception ignored) {
            // rebuild below
        }
    }

    def builtIndex = collectProjectAnnotationIndex( proj, ownerStage, omittedMdKeys )
    def timestamp = LocalDateTime.now().format(
        DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss" )
    )

    def cacheObj = [
        timeStamp  : timestamp,
        fingerprint: currentFingerprint,
        index      : builtIndex
    ]

    cacheFile.text = outputGson.toJson(cacheObj)
    return cacheObj
}

def projectCache = loadProjectCache( project, fieldCacheFile )


// --- PREFERENCES POPUP ---
def codePreferences = runOnFxThreadAndWait {
    
    def dialog = new Dialog()
    dialog.setTitle( "What should be saved?" )
    
    def root = new BorderPane()
    root.setPrefHeight( 350 )
    
    dialog.getDialogPane().buttonTypes.setAll( ButtonType.OK, ButtonType.CANCEL )
    def okBtn = dialog.getDialogPane().lookupButton( ButtonType.OK )
    okBtn.setText( " Save" )
    addIcon( okBtn, FontAwesome.Glyph.FLOPPY_ALT, 14 )
    
    
    
    // CENTER PANEL: PathClass selection list
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
            if (Files.exists(summaryFile)) {
                try {
                    def raw = new String(Files.readAllBytes(summaryFile))
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
            totalsByClass.merge(className, count, Integer.&sum)
        }
    }
    pool.shutdown()
    
    // Only classes with existing annotations in project, sorted by abundance
    List pathClassCBsMap = totalsByClass
        .collect { [name: it.key, data: it.key, num: it.value] }
        .sort { -it.num }
        .collect { [name: it.name, data: it.data] } // labels set by updateClassCounts
    def pathClassBox = makeCheckboxBox( "Available classes", null, pathClassCBsMap, 200 )
    pathClassBox.header.getChildren().addAll( pathClassBox.selectAll )
    pathClassBox.CBs.each { it.setSelected( it.getUserData() in prePrefsMap.objectClasses ) }
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
    
    
    
    // RIGHT PANEL: Measurements and metadata
    def measurementsBox
    def metadataBox
    def bottomRightPanel = new HBox( 10 )
    
    def selectSpecificCBs = { CBs, List matches ->
        CBs.each { it.setSelected( it.getUserData() in matches ) }
    }
    
    // Closure to generate available measurements and metadata options by selected object class
    def setRightBox = {

        def measurementsByClass = projectCache.index.measurementsByClass ?: [:]
        LinkedHashSet allMeasurements = new LinkedHashSet( (measurementsByClass.values() ?: []).collectMany { it ?: [] } )
        def allMeasurementsMap = allMeasurements.collect { [name: it] }
        measurementsBox = makeCheckboxBox( "Available measurements", "available measurements within objects of the selected classes", allMeasurementsMap, 350 )
        measurementsBox.header.getChildren().add( measurementsBox.selectAll )
        measurementsBox.box.setMinWidth( Region.USE_PREF_SIZE )
        
        def metadataByClass = projectCache.index.metadataByClass ?: [:]
        LinkedHashSet allMetadata = new LinkedHashSet( (metadataByClass.values() ?: []).collectMany { it ?: [] } )
        def allMetadataMap = allMetadata.collect { [name: it] }
        metadataBox = makeCheckboxBox( "Available metadata", "available metadata within objects of the selected classes", allMetadataMap, 250 )
        metadataBox.header.getChildren().add( metadataBox.selectAll )
        metadataBox.box.setMinWidth( Region.USE_PREF_SIZE )
        
        pathClassBox.CBs.each { cb ->
            cb.selectedProperty().addListener { obs, oldVal, newVal ->
                def selectedPCs = pathClassBox.CBs.findAll { it.isSelected() }.collect { it.getUserData() }
                
                LinkedHashSet availableMeasurements = new LinkedHashSet( selectedPCs.collectMany { measurementsByClass[ it ] ?: [] } )
                measurementsBox.CBs.each { cbCheck ->
                    boolean match = cbCheck.getText() in availableMeasurements
                    cbCheck.setVisible( match )
                    cbCheck.setManaged( match )
                }
                
                LinkedHashSet availableMetadata = new LinkedHashSet( selectedPCs.collectMany { metadataByClass[ it ] ?: [] } )
                metadataBox.CBs.each { cbCheck ->
                    boolean match = cbCheck.getText() in availableMetadata
                    cbCheck.setVisible( match )
                    cbCheck.setManaged( match )
                }
            }
            cb.setSelected( !cb.isSelected() )
            cb.setSelected( !cb.isSelected() )
        }
        
        bottomRightPanel.getChildren().setAll( measurementsBox.box, metadataBox.box )
        
        return [ mmCBs: measurementsBox.CBs,
                 mdCBs: metadataBox.CBs ]
    }
    
    def liveRightCBs = setRightBox()
    selectSpecificCBs( liveRightCBs.mmCBs, prePrefsMap.measurements )
    selectSpecificCBs( liveRightCBs.mdCBs, prePrefsMap.metadata )
    
    
    def timeStamp = new Label( " Last refresh: ${projectCache.timeStamp}" )
    addIcon( timeStamp, FontAwesome.Glyph.CLOCK_ALT, 14 )
    timeStamp.setPadding( new Insets( 5 ) )
    def refreshBtn = new Button( " Refresh project data" )
    addIcon( refreshBtn, FontAwesome.Glyph.REFRESH, 12 )
    setTT( refreshBtn, "Re-scan project to update available object classes, measurements, and metadata.")
    def topRow = new HBox( 10, refreshBtn, timeStamp )
    
    def rightPanel = new VBox( 10, topRow, bottomRightPanel )
    
    root.right = rightPanel
    
    
    
    // BOTTOM ROW: Open file saver browser
    boolean save = false
    def filePath
    def folderPath
    okBtn.addEventFilter( ActionEvent.ACTION ) { e ->
        def chooser = new FileChooser()
        chooser.setTitle( "Choose save location" )
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter( "CSV files", "*.csv" )
        )
        
        def startingDir = prePrefsMap.folder ?
            new File( prePrefsMap.folder ) :
            project.getBaseDirectory()
    
        chooser.setInitialFileName( "results.csv" )
        chooser.setInitialDirectory( startingDir )
        
        def owner  = dialog.getDialogPane().getScene()?.getWindow()
        def chosen = chooser.showSaveDialog( owner )
        
    
        if (chosen == null) {
            e.consume()
            return
        }
        
        filePath = chosen.getAbsolutePath()
        if (!filePath.toLowerCase().endsWith( ".csv" )) {
            filePath += ".csv"
        }
        folderPath = chosen.getParent()
        
        save = true
    }
    
    
    
    // Disabling the "OK" button if not all inputs are met    
    def rebindOkBtn = {
        okBtn.disableProperty().unbind()
        def anySelectedImage = Bindings.createBooleanBinding(
            { imagesBox.CBs.any { it.isSelected() } },
            *imagesBox.CBs.collect { it.selectedProperty() }
        )
        def anySelectedClass = Bindings.createBooleanBinding(
            { pathClassBox.CBs.any { it.isSelected() } },
            *pathClassBox.CBs.collect { it.selectedProperty() }
        )
        okBtn.disableProperty().bind(
            anySelectedImage.not()
            .or( anySelectedClass.not() )
        )
    }
    
    // Initial bind
    rebindOkBtn()
    
    
    // Refresh project details
    refreshBtn.setOnAction {
        refreshBtn.setDisable( true )
        def dialogStage = dialog.getDialogPane().getScene().getWindow() as Stage
        
        List measurementsSelected = liveRightCBs.mmCBs.findResults { it.isSelected() ? it.getText() : null }
        List metadataSelected     = liveRightCBs.mdCBs.findResults { it.isSelected() ? it.getText() : null }
        
        Thread.startDaemon {
            def newCache = loadProjectCache( project, fieldCacheFile, true, dialogStage )
    
            Platform.runLater {
                projectCache = newCache
                liveRightCBs = setRightBox()
                selectSpecificCBs( liveRightCBs.mmCBs, measurementsSelected )
                selectSpecificCBs( liveRightCBs.mdCBs, metadataSelected )
                timeStamp.setText( " Last refresh: ${projectCache.timeStamp}  [CURRENT DATA]" )
                refreshBtn.setDisable( true )
                rebindOkBtn()
            }
        }
    }
    
    
    // Attach root & show dialog
    dialog.getDialogPane().content = root   
    dialog.showAndWait()
    
    if (save) {
        def selectedImages = imagesBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getText() }
        def selectedPathClasses = pathClassBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getUserData() }
        def selectedMeasurements = measurementsBox.CBs
                .findAll { it.isSelected() && it.isVisible() }
                .collect { it.getText() }
        def selectedMetadata = metadataBox.CBs
                .findAll { it.isSelected() && it.isVisible() }
                .collect { it.getText() }
        
        // Return all preferences of "OK" is selected
        return [
            selectedImages : selectedImages,
            currentImage   : currentImageCB.isSelected(),
            objectClasses  : selectedPathClasses,
            measurements   : selectedMeasurements,
            metadata       : selectedMetadata,
            file           : filePath,
            folder         : folderPath
        ]  
    }
}


// Exit if cancelling
if (codePreferences == null) {
    return
    
} else { // Write to .json if exporting/importing
    
    prefsFile.text = outputGson.toJson( codePreferences )
}



def imagesToRun = project.getImageList().findAll { it.getImageName() in codePreferences.selectedImages }

def total = imagesToRun.size()
def progressUI = createProgressWindow( "Processing", total )
def stopLaterFlag = progressUI.stopLaterFlag

// Making a matrix for all images
List completeMatrixMap = []
List headerRow = []
List emptyImages = []
boolean export = true

def nThreads = Math.min( Runtime.getRuntime().availableProcessors() * 2, imagesToRun.size() )
def pool = Executors.newFixedThreadPool( nThreads )
def rowResults = new Object[ imagesToRun.size() ]
def emptyImagesSync = Collections.synchronizedList( [] )

def futures = imagesToRun.indices.collect { idx ->
    if (stopLaterFlag.get()) return null
    pool.submit({
        def image = imagesToRun[ idx ]
        def imageData = image == currentEntry ? currentImageData : image.readImageData()
        try {
            def hierarchy = imageData.getHierarchy()
            def annotations = hierarchy.getAnnotationObjects().findAll {
                it.getPathClass()?.getName() in codePreferences.objectClasses
            }
            def pixelCalibration = imageData.getServer().getMetadata().getPixelCalibration()
            String imageName = image.getImageName()
            
            if (annotations.isEmpty()) {
                emptyImagesSync << imageName
                rowResults[ idx ] = []
                progressUI.lastImageName.set( imageName )
                progressUI.doneCount.incrementAndGet()
                return
            }
            
            def localRows = []
            annotations.each { anno ->
                def parent = anno.getParent()
                Map rowMap = [:]
                
                rowMap[ "Image" ]                = imageName
                rowMap[ "Anno_Name" ]            = anno.getName()
                rowMap[ "Anno_Class" ]           = anno.getPathClass()?.getName()
                rowMap[ "Anno_ID" ]              = anno.getID()
                rowMap[ "Anno_Area_um" ]         = anno.getROI()?.getScaledArea( pixelCalibration )
                rowMap[ "Anno_Hierarchy_Level" ] = anno.getLevel()
                rowMap[ "Parent_Name" ]          = parent?.getName()
                rowMap[ "Parent_Class" ]         = parent?.getPathClass()?.getName()
                rowMap[ "Parent_ID" ]            = parent?.getID()
                
                def MMs = anno.getMeasurementList()
                codePreferences.measurements.each { mmName ->
                    rowMap[ "Measurements_${mmName}" ] = MMs.containsKey( mmName ) ? MMs.get( mmName ) : null
                }
                
                def MD = anno.getMetadata()
                codePreferences.metadata.each { mdKey ->
                    rowMap[ "Metadata_${mdKey}" ] = MD.containsKey( mdKey ) ? MD.get( mdKey ) : null
                }
                
                localRows << rowMap
            }
            rowResults[ idx ] = localRows
        } finally {
            if (image != currentEntry) imageData.close()
        }
        
        progressUI.lastImageName.set( image.getImageName() )
        progressUI.doneCount.incrementAndGet()
    } as Callable)
}.findAll { it != null }

futures.each { it.get() }
pool.shutdown()

// Merge in order
emptyImages.addAll( emptyImagesSync )
rowResults.each { localRows -> if (localRows) completeMatrixMap.addAll( localRows ) }
headerRow = completeMatrixMap ? completeMatrixMap[0].keySet().toList() : []

if (stopLaterFlag.get()) { export = false }


// --- Final output matrix processing ---
// Remove columns where every row has null for that key
headerRow = headerRow.findAll { key ->
    completeMatrixMap.any { rowMap -> rowMap[ key ] != null }
}

// Then add the current image matrix rows
List outputMatrix = completeMatrixMap.collect { rowMap ->
    headerRow.collect { key -> rowMap[ key ] }
}

// Add header
outputMatrix.addFirst( headerRow )


// Closing progress bar window
Thread.sleep( 200 )
Platform.runLater {
    progressUI.stage.close()
}


// Firing conditional warnings if needed
if (emptyImages.size() == imagesToRun.size()) {
    Dialogs.showWarningNotification( "WARNING: No data exported", "No images contain viable annotations." )
    export = false
} else if (!emptyImages.isEmpty()) {
    Dialogs.showWarningNotification( "WARNING: Image(s) not included (no viable annotations)", " - " + emptyImages.join( "\n - ") )
}


// Exporting all data if allowed
if (export) {
    def file = new File( codePreferences.file )
    
    // Creating a CSV file if one doesn't exist (to append to)
    if (!file.exists()) {  
        file.createNewFile() 
    }
    
    // Catching any errors if the file is open
    exportAttemptLoop:
    while (true) {
        try {
            file.withWriter { writer ->
                outputMatrix.each {
                    def row = it.join( "," )
                    writer.writeLine( row )
                }
            }
            
            Dialogs.showInfoNotification( "Saved successfully!", file.toString() )
            
            break exportAttemptLoop
        
        } catch (Exception e) {
            
            boolean retry = runOnFxThreadAndWait {
                def fileOpenAlert = new Alert( Alert.AlertType.CONFIRMATION )
                
                fileOpenAlert.setTitle( "Cannot write!" )
                fileOpenAlert.setHeaderText( "\"${file.getName()}\" is open in another application..." )
                fileOpenAlert.setContentText( "Close the file and try again." )
                
                def cancelBtn   = new ButtonType( "Cancel", ButtonBar.ButtonData.CANCEL_CLOSE )
                def tryAgainBtn = new ButtonType( "Try again", ButtonBar.ButtonData.YES )
                fileOpenAlert.getDialogPane().buttonTypes.setAll( cancelBtn, tryAgainBtn )
                
                def result = fileOpenAlert.showAndWait().orElse( cancelBtn )
                return result == tryAgainBtn
            }
            
            if (!retry) { break exportAttemptLoop }
        }
    }
}


import java.nio.file.Files
import javafx.util.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javafx.animation.*
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.event.ActionEvent
import javafx.geometry.*
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.layout.*
import javafx.scene.input.*
import javafx.stage.*
import javafx.beans.binding.Bindings
import com.google.gson.GsonBuilder
import com.google.gson.Gson
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import qupath.lib.gui.tools.IconFactory
import org.controlsfx.glyphfont.FontAwesome


// Comments for tool launcher icon and tooltip
// ORDER: 8
// ICON: SAVE
// Save custom annotation information to .csv