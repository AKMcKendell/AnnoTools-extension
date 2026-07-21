/*
 * Author: Alec McKendell
 * Date: 2026.4.20
 * Description: Generate new annotations at the colocalization of others.
*/


// Class for nesting option strings
class NestStrings {
    static final String CLASS1        = "Under class 1 object"
    static final String CLASS2        = "Under class 2 object"
    static final String CLASS1_PARENT = "Under class 1 object parent"
    static final String CLASS2_PARENT = "Under class 2 object parent"
    static final String NONE          = "None"
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
                        NestStrings.CLASS1_PARENT,
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

def total = imagesToRun.size()
def progressUI = createProgressWindow( "Processing", total )
def stopNowFlag = progressUI.stopNowFlag
def stopLaterFlag = progressUI.stopLaterFlag
int processed = 0
int netSteps = codePreferences.objectClasses1.size() * codePreferences.objectClasses2.size()

// Pool for parallel processing
def pool = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() )
progressUI.poolRef.set( pool )

imageLoop:
for (image in imagesToRun) {
    // Stopping when requested
    if (stopNowFlag.get() || stopLaterFlag.get()) { break }
    
    // Reporting current image
    updateProgressWindow( progressUI, [stepMax: netSteps, imageName: image, imageNum: processed+1] )
    
    // If working on current image, modifying respective image data
    def imageData = image == currentEntry ? currentImageData : image.readImageData()
    def hierarchy = imageData.getHierarchy()
    def annotations = hierarchy.getAnnotationObjects()
    def annosByClass = annotations.groupBy { it.getPathClass()?.getName() }
    
    int step = 1
    
    // --- EXECUTION ---
    class1Loop:
    for (class1 in codePreferences.objectClasses1) {
        
        class2Loop:
        for (class2 in codePreferences.objectClasses2) {
            
            String newClass = class1 + "-" + class2 
            updateProgressWindow( progressUI, [stepNum: step, stepName: "Working on ${newClass}"] )
            
            // Adding the new class to the pathclass list if it is not already on it
            def availableClasses = getQuPath().getAvailablePathClasses()               
            if (!availableClasses.contains( getPathClass( newClass )) ) {
                availableClasses << getPathClass( newClass )
            }               
       
            // Clearing objects if they already exist to not double add
            def oldObjects = annotations.findAll {
                it.getPathClass()?.getName() == newClass
            }
            hierarchy.removeObjects( oldObjects, false )
            
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

            // Build index + cache geometries once before the loop
            def class2GeomCache = class2Annos.collect { cleanForROI( it.getROI().getGeometry() ) }
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

            // Parallel geometry phase
            def futures = (0..<numClass1).collect { idx ->
                if (stopNowFlag.get()) { return null }
                try {
                    pool.submit({
                        def class1Anno = class1Annos[ idx ]
                        def class1Geom = cleanForROI( class1Anno.getROI().getGeometry() )
                        def parent = (codePreferences.nestBehavior == NestStrings.CLASS1)        ? class1Anno :
                                     (codePreferences.nestBehavior == NestStrings.CLASS1_PARENT) ? class1Anno.getParent() :
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
                        updateProgressWindow( progressUI, [ stepPer: pDone ] )
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