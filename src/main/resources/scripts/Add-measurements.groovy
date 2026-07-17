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
def createProgressWindow( String title, int totalImages ) {
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
def makeCheckboxBox( String title, String keyWord = null, List objects, int prefWidth = 0, int prefHeight = 0 ) {
    
    def checkboxBox = new VBox( 5 )
    checkboxBox.setPadding( new Insets( 5 ) )
    if (prefWidth > 0) {
        checkboxBox.setPrefWidth( prefWidth )
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
    
    checkboxes.each { checkboxContainer.getChildren().add( it ) }
    
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
    
    // Add listeners to checkboxes (for changing instructions/labels)
    checkboxes.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal ->            
            
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
    def projectPathClasses = project.getPathClasses().findAll { it?.getName() }
    
    def gson = new Gson()
    def imageClassCounts = [:]  // imageName -> [className -> count]
    def totalsByClass = [:]     // className -> total across all images
    
    def imageList = project.getImageList()
    def nThreads = Math.min( Runtime.getRuntime().availableProcessors(), imageList.size() )
    def pool = Executors.newFixedThreadPool( nThreads )
    def resultMap = new java.util.concurrent.ConcurrentHashMap()
    
    // Parallel finding anno summaries
    def futures = imageList.collect { entry ->
        pool.submit({
            def summaryFile = entry.getEntryPath().resolve( "summary.json" )
            def counts = [:]
            if (Files.exists( summaryFile )) {
                try {
                    def raw = new String( Files.readAllBytes( summaryFile ) )
                    def summary = gson.fromJson( raw, Object )
                    summary?.hierarchy?.annotationClassificationCounts?.each { k, v ->
                        def n = k.toString()
                        counts[ n ] = (v instanceof Number) ? ((Number)v).intValue() : 0
                    }
                } catch (Exception ignored) {}
            }
            resultMap[ entry.getImageName() ] = counts
        } as Callable)
    }
    futures.each { it.get() }
    pool.shutdown()
    
    // Merge into imageClassCounts and totalsByClass
    resultMap.each { imgName, counts ->
        imageClassCounts[ imgName ] = counts
        counts.each { className, count ->
            totalsByClass[ className ] = (totalsByClass[ className ] ?: 0) + count
        }
    }
    
    // Sort: only classes with existing annotations in project by abundance
    def highCounts = []
    projectPathClasses.each { pc ->
        def total = totalsByClass[ pc.getName() ] ?: 0
        if (total > 0) { highCounts << [name: pc.getName(), num: total] }
    }
    highCounts.sort { -it.num }
    def pathClassNamesAndNum = highCounts
    List pathClassCBsMap = pathClassNamesAndNum.collect { [name: it.name, data: it.name] } // labels set by updateClassCounts
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
    
    // INTENSITY MEASUREMENTS
    // Getting comomon available channels across project images
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
    
    okBtn.disableProperty().bind(
        anySelectedImage.not()
        .or( anySelectedClass.not() )
        .or( addIntensityMeasurementsCB.selectedProperty().not()
            .and( addMorphologyMeasurementsCB.selectedProperty().not() ) )
        .or( addIntensityMeasurementsCB.selectedProperty()
            .and( anySelectedChannel.not()
                .or ( anySelectedIntensityMeasurement.not() ) ) )
        .or( addMorphologyMeasurementsCB.selectedProperty()
            .and( anySelectedMorphologyMeasurement.not() ) )
    )
    
    
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

def total = imagesToRun.size()
def progressUI = createProgressWindow( "Processing", total )
def stopNowFlag   = progressUI.stopNowFlag
def stopLaterFlag = progressUI.stopLaterFlag
int processed = 0
int netSteps = (codePreferences.addIntensityMeasurements ? 1 : 0) +
               (codePreferences.addMorphologyMeasurements ? 1 : 0)

def pool = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() )
progressUI.poolRef.set( pool )

try {
    imageLoop:
    for (image in imagesToRun) {
        // Stopping when requested
        if (stopNowFlag.get() || stopLaterFlag.get()) { break }
        
        // Reporting current image
        updateProgressWindow( progressUI, [stepMax: netSteps, imageName: image] )
        
        // If working on current image, modifying respective image data
        def imageData = image == currentEntry ? currentImageData : image.readImageData()
        
        def server = imageData.getServer()
        def metadata = server.getMetadata()
        def channels = metadata.getChannels()
        double pixelConversionFactor = metadata.getAveragedPixelSize()
        
        // Filter annotations of interest
        List annotations = imageData.getHierarchy().getAnnotationObjects().findAll {
            it.getPathClass()?.getName() in codePreferences.pathClasses
        }
        
        int step = 1
        
        if (codePreferences.addIntensityMeasurements) {
            
            updateProgressWindow( progressUI, [stepNum: step, stepName: "Adding intensity measurements"] )
            
            // Create server with only the desired channels to reduce processing time (otherwise will do all channels)
            def modifiedChannelServer = new TransformedServerBuilder( server )
                .extractChannels( *codePreferences.intensityChannels )
                .build()
            
            def doneCount = new AtomicInteger( 0 )
            int numAnnos = annotations.size()
            int lastPct = -1
            
            // Parallel processing the measurements
            def futures = annotations.collect { anno ->
                if (stopNowFlag.get()) { return null }
                try {
                    pool.submit({
                        ObjectMeasurements.addIntensityMeasurements(
                            modifiedChannelServer,
                            anno,
                            pixelConversionFactor,
                            codePreferences.intensityMeasurements,
                            []
                        )
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
        updateProgressWindow( progressUI, [imagePer: frac, imageNum: processed] )
    }
} finally {
    pool.shutdown()
    progressUI.poolRef.set( null )
}

// Closing progress bar window
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

import qupath.lib.gui.tools.IconFactory
import org.controlsfx.glyphfont.FontAwesome


// Comments for tool launcher icon and tooltip
// ORDER: 5
// ICON: PLUS_CIRCLE
// Add intensity or morphology measurements to annotations