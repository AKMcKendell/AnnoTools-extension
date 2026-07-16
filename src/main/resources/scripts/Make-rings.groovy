/*
 * Author: Alec McKendell
 * Date: 2026.3.29
 * Description: Creates concentric rings around annotations of interest.
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
                Tooltip.install(obj, newTT)
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
        
        def cancelBtn = new Button( "Cancel" )
        cancelBtn.setDisable( false )
        def spacer = new Region()
        HBox.setHgrow( spacer, Priority.ALWAYS )
        def timerLabel = new Label( "Elapsed: 00:00:00" )       
        def firstBottomRow = new HBox( 10, cancelBtn, spacer, timerLabel )
        firstBottomRow.setAlignment( Pos.CENTER_LEFT )
        
        List rows = [stepRow, imageRow, barRow, firstBottomRow]
        
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
                Duration.seconds( 1 ),
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
        
        // Signal to cancel script
        def cancelFlag = new AtomicBoolean( false )
        def cancelUpdateLabel = new Label( "Cancelling after image..." )
        cancelUpdateLabel.setPadding( new Insets( 4 ) )
        cancelBtn.setOnAction {
            firstBottomRow.getChildren().set(
                firstBottomRow.getChildren().indexOf( cancelBtn ), cancelUpdateLabel
            )
            cancelFlag.set( true )
        }
        stage.setOnCloseRequest { evt ->
            cancelFlag.set( true )
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
            cancelFlag   : cancelFlag
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

// Function: Take a list of annotations and return a list of corresponding geometries with filled holes
def fillAnnoHoles( List annoGeoms ) {    
    
    def filledGeomsList = []
    
    annoGeoms.each { annoGeom ->       
        def geomFactory = annoGeom.getFactory()    
        Geometry filledGeom
    
        if (annoGeom instanceof Polygon) {
            filledGeom = geomFactory.createPolygon( annoGeom.getExteriorRing() )
        } else if (annoGeom instanceof MultiPolygon) {
            def polys = []
            for (i in 1..annoGeom.getNumGeometries()) {
                def p = annoGeom.getGeometryN( i-1 )
                polys << geomFactory.createPolygon( p.getExteriorRing() )
            }
            filledGeom = geomFactory.createMultiPolygon( polys as Polygon[] )
        } else {
            filledGeom = annoGeom // fallback
        }    
        filledGeomsList << filledGeom
    }    
    return filledGeomsList
}

// Function: Recursively extract polygonal parts from mixed geometries
// This is because modifying geometries using built in functions can fail if either shape is a GeometryCollection
def getPolygonParts( Geometry g_parts ) {
    if (g_parts instanceof Polygonal)
        return [g_parts]

    if (g_parts instanceof GeometryCollection) {
        def parts = []
        for (i in 1 .. g_parts.getNumGeometries()) {
            parts.addAll( getPolygonParts( g_parts.getGeometryN( i-1 ) ) )
        }
        return parts
    }

    return []
}

// Function: Make a geometry into a flattened polygon for easier manipulation without erroring
def toPolygonGeometry( Geometry g_toPoly ) {
    def parts = getPolygonParts( g_toPoly )
    if (parts.isEmpty()) { return g_toPoly.getFactory().createPolygon() }
    return parts.size() == 1 ? parts[0] : UnaryUnionOp.union( parts )
}

// Function: Final safety wrapper for ROI conversion; uses buffer(0) only if the cleaned geometry is invalid
def cleanForROI( Geometry g_clean ) {
    if (g_clean == null || g_clean.isEmpty()) {
        return g_clean?.getFactory()?.createPolygon()
    }
    
    def cleaned = toPolygonGeometry( g_clean )
    
    if (cleaned == null || cleaned.isEmpty()) {
        return g_clean.getFactory().createPolygon()
    }

    if (!cleaned.isValid()) {
        cleaned = cleaned.buffer( 0 ) // fallback only
    }
    
    return cleaned
}

// Function to simplify geometry for speed based on passed max vertices
def simplifyGeom( Geometry g_simple, int targetMaxPoints = 1000 ) {
    int pts = g_simple.getNumPoints()
    if (pts <= targetMaxPoints) { return g_simple }
    def env = g_simple.getEnvelopeInternal()
    double diag = Math.sqrt( env.getWidth()**2 + env.getHeight()**2 )
    double tol = diag / targetMaxPoints
    return TopologyPreservingSimplifier.simplify( g_simple, tol )
}

// Function to break shape into parts if multiple in a mask (speeds up processing)
def bufferGeom( Geometry g_buff, double dist, bufParams ) {
    int n = g_buff.getNumGeometries()
    if (n <= 1) { return BufferOp.bufferOp( g_buff, dist, bufParams ) }
    def parts = (0..<n).collect { g_buff.getGeometryN( it ) }
    def buffered = parts.parallelStream()
        .map { part ->
            def b = BufferOp.bufferOp( part, dist, bufParams )
            return b.isValid() ? b : b.buffer( 0 )
        }
        .collect( java.util.stream.Collectors.toList() )
    return toPolygonGeometry( CascadedPolygonUnion.union( buffered ) )
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

// Function to create icon for UI
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
def scriptPrefsFileName   = "Make-Rings_Preferences" + ".json"

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
    dialog.setTitle( "What rings should be generated?" )
    
    def root = new BorderPane()
    root.setPrefHeight( 350 )
    
    dialog.getDialogPane().buttonTypes.addAll( ButtonType.OK, ButtonType.CANCEL )
    def okBtn = dialog.getDialogPane().lookupButton( ButtonType.OK )
    okBtn.setText( "Make rings" )
    ButtonBar.setButtonUniformSize( okBtn, false )
    
    
    
    // TOP ROW: Expansion distances
    int maxExpansions = 5
    List<Integer> expansions = []
    
    // Parse comma-separated integers
    def parseIntegerList = { String text ->    
        if (!text?.trim())
            return []     
        def values = []    
        text.split( "," ).each { part ->
            def trimmed = part.trim()
            values << trimmed.toInteger()
        }
        return values
    }
    
    def expansionLabel = new Label( " Expansion radii (μm):" )
    addIcon( expansionLabel, FontAwesome.Glyph.EXPAND, 14 )
    def expansionField = new TextField()
    expansionField.setPromptText( "e.g. \"6,16,31\"" )
    setTT( [expansionLabel, expansionField], "Radii of expansion rings using numbers and commas only (max ${maxExpansions})." )
    
    def topSpacer = new Region()
    HBox.setHgrow( topSpacer, Priority.ALWAYS )
    def outputClassPreview = new Label()
    setTT( outputClassPreview, "Preview of output class name(s)" )
    addIcon( outputClassPreview, FontAwesome.Glyph.EYE, 14 )
    def classPrev = new SimpleStringProperty( "Class" )
    
    // Rebuilds the preview text from the current class + up to 3 expansions
    def updateClassPreview = {
        def cls = classPrev.get()
        if (expansions.isEmpty()) {
            outputClassPreview.setText( " Output classes preview:  ${cls}_Exp" )
            return
        }
        def examples = expansions.take( 3 ).collect { "${cls}_${it}" }
        if (expansions.size() > 3) {
            examples << "..."
        }
        outputClassPreview.setText( " Output classes preview:  " + examples.join( ", " ) )
    }
    
    updateClassPreview()
    
    def previewLabel = new Label( "=" )  
    def expansionsPreview = new Label( "[]" )
    setTT( [previewLabel, expansionsPreview], "Preview of final expansions.")
    
    
    expansionField.textProperty().addListener { obs, oldVal, newVal ->
        
        // Allow only digits and commas (no double commas, no leading comma)
        if (!newVal.matches( "[\\d,]*" ) || newVal.contains( ",," ) || newVal.startsWith( "," )) {
            expansionField.setText( oldVal )
            return
        }
        
        // Enforce max 4 digits per number, no leading zeros
        if (newVal.split( "," ).any { it.length() > 4 || it.startsWith( "0" ) }) {
            expansionField.setText( oldVal )
            return
        }
        
        // Capped at 5 expansions
        List<Integer> newExpansions = parseIntegerList( newVal ).unique().sort()
        if (newExpansions.size() > maxExpansions ||
                (newExpansions.size() == maxExpansions && newVal.endsWith( "," ))) {
            expansionField.setText( oldVal )
            return
        }
        
        expansions = newExpansions
        updateClassPreview()
        expansionsPreview.setText( expansions.toString() )
    }
    
    // Setting the expansions to previous values
    boolean correctCriteria = prePrefsMap.expansionsMicrons instanceof List &&
        prePrefsMap.expansionsMicrons.every { it instanceof Number } &&
        prePrefsMap.expansionsMicrons.size() <= maxExpansions
    expansionField.setText( correctCriteria ? 
        prePrefsMap.expansionsMicrons.collect { it.intValue().toString() }.join( "," ) :
        ""
    )
    
    def expansionRow = new HBox( 8, expansionLabel, expansionField, previewLabel, expansionsPreview, topSpacer )
    expansionRow.setAlignment( Pos.CENTER_LEFT )
    def classPrevRow = new HBox( 8, outputClassPreview ) 
    def topBox = new VBox( 8, expansionRow, classPrevRow )
    topBox.setPadding( new Insets( 5, 0, 20, 5 ) )
    topBox.setAlignment( Pos.CENTER_LEFT )
    
    // Add it to your existing layout
    root.top = topBox
    
    
    
    // RIGHT PANEL: PathClass selection list
    def projectPathClasses = project.getPathClasses().findAll { it?.getName() }
    
    def gson = new Gson()
    def imageClassCounts = [:]  // imageName -> [className -> count]
    def totalsByClass = [:]     // className -> total across all images
    
    project.getImageList().each { entry ->
        def summaryFile = entry.getEntryPath().resolve( "summary.json" )
        def counts = [:]
        if (Files.exists( summaryFile )) {
            def raw = new String( Files.readAllBytes( summaryFile ) )
            def summary = gson.fromJson( raw, Object )
            summary?.hierarchy?.annotationClassificationCounts?.each { k, v ->
                def n = k.toString()
                counts[ n ] = (v instanceof Number) ? ((Number)v).intValue() : 0
                totalsByClass[ n ] = (totalsByClass[ n ] ?: 0) + counts[ n ]
            }
        }
        imageClassCounts[ entry.getImageName() ] = counts
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
    pathClassBox.CBs.each { it.setSelected( it.getUserData() in prePrefsMap.objectClasses ) }
    
    // Adding listeners to stop same class from being selected twice and updating class preview
    pathClassBox.CBs.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, selected ->
            def firstSelected = pathClassBox.CBs.find { it.isSelected() }
            classPrev.set( selected ? cb.getUserData() : firstSelected ? firstSelected.getUserData() : "Class" )
            updateClassPreview()
        }
        
        cb.setSelected( !cb.isSelected() )
        cb.setSelected( !cb.isSelected() )
    }
    
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
            currentCB.lookup( ".box" )?.setStyle("""
                -fx-border-color: black;
                -fx-border-width: 2;
                -fx-border-radius: 1;
            """)
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
    
    
    
    // BOTTOM ROW:
    def expandOnlyOutwardCheck = new CheckBox( "Only expand outward" )
    setTT( expandOnlyOutwardCheck, "Expand only outward rather than also inward if central object has holes?")
    expandOnlyOutwardCheck.setSelected( prePrefsMap.expandOnlyOutward instanceof Boolean ? prePrefsMap.expandOnlyOutward : true )
    
    def contstrainToParentCheck = new CheckBox( "Constrain to parent" )
    contstrainToParentCheck.setPadding( new Insets( 0, 0, 0, 20 ) )
    setTT( contstrainToParentCheck, "Crop expansions to the parent object of central object?")
    contstrainToParentCheck.setSelected( prePrefsMap.constrainToParent instanceof Boolean ? prePrefsMap.constrainToParent : true )
    
    def firstBottomSpacer = new Region()
    HBox.setHgrow( firstBottomSpacer, Priority.ALWAYS )
    
    def simplifyGeomCheck = new CheckBox( "Simplify geometry" )
    setTT( simplifyGeomCheck, """If expanding complex objects is taking too long, this will simplify the shapes.
                               \nWARNING: The geometry will be inexact.""" )
    simplifyGeomCheck.setSelected( prePrefsMap.simplifyGeometry instanceof Boolean ? prePrefsMap.simplifyGeometry : false )
    
    def secondBottomSpacer = new Region()
    HBox.setHgrow( secondBottomSpacer, Priority.ALWAYS )
    
    def simpleLabel = new Label( "Simple" )
    def complexLabel = new Label( "Complex" )
    [simpleLabel, complexLabel].each {
        it.setStyle("""
            -fx-font-size: 12px;
            -fx-text-fill: gray;
        """)
    }
    
    def roundTo2 = { num ->
        return Math.round( num.doubleValue() / 2.0 ) * 2.0
    }
    
    def maxVerticesSlider = new Slider( 2, 10, 6 )
    maxVerticesSlider.setPrefWidth( 100 )
    setTT( maxVerticesSlider, "Max number of vertices x1000 before simplifying for expansions. More is finer but slower." )
    maxVerticesSlider.setMajorTickUnit( 2 )
    maxVerticesSlider.setMinorTickCount( 0 )
    maxVerticesSlider.setSnapToTicks( true )   
    maxVerticesSlider.setShowTickMarks( true )    
    maxVerticesSlider.setShowTickLabels( true )
    if (prePrefsMap.maxVertices instanceof Number) { 
        maxVerticesSlider.setValue( roundTo2( prePrefsMap.maxVertices / 1000 ).toInteger() )
    }
    
    maxVerticesSlider.valueProperty().addListener { obs, oldVal, newVal ->
        if (!maxVerticesSlider.isValueChanging()) { return }
    
        double snapped = roundTo2( newVal )
        if (maxVerticesSlider.getValue() != snapped) { maxVerticesSlider.setValue( snapped ) }
    }
    
    // Creating row and adding children
    def firstBottomRow = new HBox( 10 )
    firstBottomRow.setPadding( new Insets( 20, 10, 0, 10 ) )
    firstBottomRow.getChildren().addAll(
        expandOnlyOutwardCheck,
        contstrainToParentCheck,
        firstBottomSpacer,
        simplifyGeomCheck
    )
    
    def secondBottomRow = new HBox( 5 )
    secondBottomRow.setPadding( new Insets( 10, 0, 0, 0 ) )
    secondBottomRow.getChildren().addAll(
        secondBottomSpacer,
        simpleLabel,
        maxVerticesSlider,
        complexLabel
    )
    
    secondBottomRow.visibleProperty().bind( simplifyGeomCheck.selectedProperty() )
    secondBottomRow.managedProperty().bind( simplifyGeomCheck.selectedProperty() )
    
    def bottomBox = new VBox()
    bottomBox.getChildren().addAll(
        firstBottomRow,
        secondBottomRow
    )
    
    root.bottom = bottomBox
    
    
    
    // Disabling the "OK" button if not all inputs are met    
    def anySelectedImage = Bindings.createBooleanBinding(
        { imagesBox.CBs.any { it.isSelected() } },
        *imagesBox.CBs.collect { it.selectedProperty() }
    ) 
    def anySelectedPathClass = Bindings.createBooleanBinding(
        { pathClassBox.CBs.any { it.isSelected() } },
        *pathClassBox.CBs.collect { it.selectedProperty() }
    )  
    def hasExpansions = Bindings.createBooleanBinding(
        { !parseIntegerList( expansionField.getText() ).isEmpty() },
        expansionField.textProperty()
    )
    def onlyZero = Bindings.createBooleanBinding(
        { parseIntegerList( expansionField.getText() ) == [0] },
        expansionField.textProperty()
    )
    
    okBtn.disableProperty().bind(
        anySelectedImage.not()
            .or( anySelectedPathClass.not() )
            .or( hasExpansions.not() )
            .or( onlyZero )
    )
    
    
    // Attach root & show dialog
    dialog.getDialogPane().content = root
    
    def result = dialog.showAndWait()
    def button = result.get()
    
    if (button == ButtonType.OK) {                
        def selectedImages = imagesBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getText() }
        def selectedPathClasses = pathClassBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getUserData() }
        
        // Return all preferences of "OK" is selected
        return [
            selectedImages    : selectedImages,
            currentImage      : currentImageCB.isSelected(),
            objectClasses     : selectedPathClasses,
            expansionsMicrons : expansions,
            expandOnlyOutward : expandOnlyOutwardCheck.isSelected(),
            constrainToParent : contstrainToParentCheck.isSelected(),
            simplifyGeometry  : simplifyGeomCheck.isSelected(),
            maxVertices       : maxVerticesSlider.getValue().toInteger() * 1000
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



def imagesToRun = project.getImageList().findAll { it.getImageName() in codePreferences?.selectedImages }

def total = imagesToRun.size()
def progressUI = createProgressWindow( "Processing", total )
def cancelFlag = progressUI.cancelFlag
int processed = 0

// Number of vertices calculated when buffering geometries (standard is 8)
def bufParams = new BufferParameters( codePreferences.simplifyGeometry ? 4 : 8 )

imageLoop:
for (image in imagesToRun) {
    
    if (cancelFlag.get()) {
        break
    }
    
    // Reporting current image
    updateProgressWindow( progressUI, [imageName: image] )
    
    // Getting image-specific information
    // If working on current image, modifying respective image data
    def imageData = image == currentEntry ? currentImageData : image.readImageData()
    def cal = imageData.getServer().getPixelCalibration()
    double pixelSize = cal.getAveragedPixelSizeMicrons()
    
    def hierarchy = imageData.getHierarchy()
    
    int netSteps = (codePreferences.objectClasses.size() * ((codePreferences.expansionsMicrons.size() * 2) + 1))
    updateProgressWindow( progressUI, [stepMax: netSteps] )
    int step = 1
    
    
    pathclassLoop:
    for (objectClass in codePreferences.objectClasses) {
        
        // Get central objects
        def baseObjs = hierarchy.getAnnotationObjects().findAll {
            it.getPathClass()?.getName() == objectClass && 
            it.getROI()?.getArea() > 0
        }
        baseObjs.each { it.setLocked( true ) }
        
        // Get expansion class names
        def expansionClasses = codePreferences.expansionsMicrons.collect { exp ->
            def expPathClassName = objectClass + "_" + exp
            return expPathClassName
        }
        
        // Clear previous expansions
        def objectsToRemove = hierarchy.getAnnotationObjects().findAll { 
            def pathClassName = it.getPathClass()?.getName()
            pathClassName in expansionClasses ||
            ( pathClassName.contains( objectClass + "_" ) && it.getParent() in baseObjs )
        }
        hierarchy.removeObjects( objectsToRemove, false )
        
        // Making sure new class(es) are in project classes
        def availableClasses = getQuPath().getAvailablePathClasses()
        def missingClasses = expansionClasses
                .collect { getPathClass( it ) }
                .findAll { it !in availableClasses }
        availableClasses.addAll( missingClasses )
        
        
        // --- RING GENERATION ---
        // Getting map with central object and relevant expansion geometries
        updateProgressWindow( progressUI, [stepNum: step, stepName: "Generating expansion map for ${objectClass}"] )
        
        // Parallelize execution
        int numBaseObjs = baseObjs.size()
        def nThreads = Math.min( Runtime.getRuntime().availableProcessors(), numBaseObjs )
        def pool = Executors.newFixedThreadPool( nThreads )
        def expansionsMap = new Object[ numBaseObjs ]
        def doneCount = new AtomicInteger( 0 )
        
        def futures = (0..<numBaseObjs).collect { idx ->
            pool.submit({
                def annoCentral = baseObjs[ idx ]
                def geomCentral = codePreferences.simplifyGeometry ?
                    simplifyGeom( toPolygonGeometry( annoCentral.getROI().getGeometry() ), codePreferences.maxVertices ) :
                    toPolygonGeometry( annoCentral.getROI().getGeometry() )
                
                if (codePreferences.expandOnlyOutward) {
                    geomCentral = toPolygonGeometry( fillAnnoHoles( [geomCentral] )[0] )
                }
                
                def geomsAll = [ geomCentral ]
                codePreferences.expansionsMicrons.each { exp ->
                    double expandPixels = exp / pixelSize
                    geomsAll << toPolygonGeometry( bufferGeom( geomCentral, expandPixels, bufParams ) )
                }
                expansionsMap[ idx ] = [ centerObj: annoCentral, allGeoms: geomsAll ]
                
                int pDone = ( doneCount.incrementAndGet() / numBaseObjs * 100 ) as int
                updateProgressWindow( progressUI, [ stepPer: pDone ] )
            } as Callable)
        }
        futures.each { it.get() }
        
        def expansionsMapList = expansionsMap.toList()
        
        
        // Prep each group: geometries are already normalized by above so no re-cleaning needed.
        // Parent geometry is cached here once so it isn't recomputed on every ring level below.
        expansionsMapList.each { objGroup ->
            objGroup.cleanedAllGeoms = objGroup.allGeoms
            objGroup.ringObjs = []
            if (codePreferences.constrainToParent) {
                def parent = objGroup.centerObj.getParent()
                objGroup.parentGeom = (parent?.getROI() != null) ? cleanForROI( parent.getROI().getGeometry() ) : null
            }
        }
        
        
        // Creating rings and nesting under central object
        expansionClasses.eachWithIndex { expClass, level ->
            
            // Reporting current step
            step ++
            updateProgressWindow( progressUI, [stepName: "Building ${expClass} inner mask", stepPer: 0] )
            
            def innerGeomsAtLevel = expansionsMapList.collect { it.cleanedAllGeoms[ level ] }
            def geoms = Collections.synchronizedList( codePreferences.simplifyGeometry ?
                innerGeomsAtLevel.collect { toPolygonGeometry( simplifyGeom( it, codePreferences.maxVertices ) ) } :
                innerGeomsAtLevel )
            int totalGeoms = geoms.size()
            
            while (geoms.size() > 1) {
                def pairs = []
                for (int i = 0; i < geoms.size() - 1; i += 2) {
                    pairs << [i, i + 1]
                }
                
                def roundResults = new Object[ pairs.size() + (geoms.size() % 2) ]
                def currentGeoms = geoms
                
                def mergeFutures = pairs.indices.collect { pi ->
                    def a = currentGeoms[ pairs[pi][0] ]
                    def b = currentGeoms[ pairs[pi][1] ]
                    def resultIdx = pi
                    pool.submit({
                        roundResults[ resultIdx ] = toPolygonGeometry( a.union( b ) )
                    } as Callable)
                }
                mergeFutures.each { it.get() }
                
                if (geoms.size() % 2 == 1) {
                    roundResults[ pairs.size() ] = geoms.last()
                }
                
                geoms = roundResults.toList()
                int pDone = ((totalGeoms - geoms.size()) / totalGeoms * 100) as int
                updateProgressWindow( progressUI, [stepPer: pDone] )
            }
            def combinedInnerMask = cleanForROI( geoms[0] )
            
            
            step++
            updateProgressWindow( progressUI, [stepNum: step, stepName: "Building ${expClass} rings", stepPer: 0] )
            
            // Parallel geometry phase
            def ringResults = new Object[ expansionsMapList.size() ]
            def doneRingCount = new AtomicInteger( 0 )
            int numExpMapVals = expansionsMapList.size()
            
            def diffFutures = (0..<numExpMapVals).collect { idx ->
                pool.submit({
                    def objGroup = expansionsMapList[ idx ]
                    def ringGeom = codePreferences.simplifyGeometry ? 
                        simplifyGeom( objGroup.cleanedAllGeoms[ level + 1 ], codePreferences.maxVertices ) :
                        objGroup.cleanedAllGeoms[ level + 1 ]
            
                    if (!combinedInnerMask.isEmpty() && ringGeom.intersects( combinedInnerMask )) {
                        ringGeom = cleanForROI( ringGeom.difference( combinedInnerMask ) )
                    }
            
                    if (codePreferences.constrainToParent) {
                        def parentGeom = objGroup.parentGeom
                        if (parentGeom != null && !ringGeom.isEmpty() && ringGeom.getArea() > 0
                                && !parentGeom.covers( ringGeom )) {
                            ringGeom = cleanForROI( ringGeom.intersection( parentGeom ) )
                        }
                    }
            
                    ringResults[ idx ] = (!ringGeom.isEmpty() && ringGeom.getArea() > 0) ? ringGeom : null
            
                    int pDone = (doneRingCount.incrementAndGet() / numExpMapVals * 100) as int
                    updateProgressWindow( progressUI, [ stepPer: pDone ] )
                } as Callable)
            }
            diffFutures.each { it.get() }
            
            // Serial hierarchy adds
            runOnFxThreadAndWait {
                ringResults.eachWithIndex { ringGeom, idx ->
                    if (ringGeom == null) return
                    def objGroup = expansionsMapList[ idx ]
                    def centerObj = objGroup.centerObj
                    def ringROI = GeometryTools.geometryToROI( ringGeom, centerObj.getROI().getImagePlane() )
                    def ringObj = PathObjects.createAnnotationObject( ringROI, getPathClass( expClass ) )
                    hierarchy.addObjectBelowParent( centerObj, ringObj, false )
                    objGroup.ringObjs << ringObj
                    ringObj.setLocked( true )
                }
            }
        }
        
        pool.shutdown()
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
    updateProgressWindow( progressUI, [imagePer: frac, imageNum: processed+1] )
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

import org.locationtech.jts.geom.*
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.union.*
import org.locationtech.jts.simplify.TopologyPreservingSimplifier

import javafx.scene.control.TextFormatter

import java.util.function.UnaryOperator

import qupath.lib.gui.tools.IconFactory
import org.controlsfx.glyphfont.FontAwesome


// Comments for tool launcher icon and tooltip
// ORDER: 2
// ICON: ARROWS_ALT
// Create concentric rings around annotations