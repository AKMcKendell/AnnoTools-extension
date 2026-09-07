/*
 * Author: Alec McKendell
 * Date: 2025.2.4
 * Description: Exports/imports annotation objects with stored parent info for re-nesting.
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
        
        def imageNum = new SimpleStringProperty( "0" )
        def imageName = new SimpleStringProperty( "Starting..." )
        def imageLabel = new Label()
        imageLabel.textProperty().bind(
            Bindings.concat( "Image ", imageNum, "/", totalImages, ": ", imageName )
        )      
        def imageRow = new HBox( 5, imageLabel )
        
        def bar = new ProgressBar( 0.0 )
        
        // Progress bar fills row width
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
        
        def updateStageWidth = {
            Platform.runLater {
                double maxRowPref = rows.collect { it.prefWidth(-1) }.max()
                double needed = Math.max( maxRowPref + vbox.getPadding().getLeft() + vbox.getPadding().getRight(), 350 )
                if (needed > stage.getWidth()) {
                    stage.setWidth( needed )
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

        stopBtn.setOnAction {
            showStopConfirmation()
        }
        stage.setOnCloseRequest { evt ->
            evt.consume()
            showStopConfirmation()
        }
        
        return [
            stage        : stage,
            imageNum     : imageNum,
            imageName    : imageName,
            bar          : bar,
            stopLaterFlag: stopLaterFlag
        ]
    }
}

// Function: Update progress window
def updateProgressWindow( UI, Map args = [:] ) {
    String imageNum  = args.imageNum
    String imageName = args.imageName
    double imagePer  = args.imagePer ?: 0

    Platform.runLater {
        if (imageName) { UI.imageName.set( imageName ) }
        if (imageNum)  { UI.imageNum.set( imageNum ) }
        if (imagePer)  { UI.bar.setProgress( imagePer ) }
    }
}

// Function: Resolve hierarchy
def resolveHierarchy( objects, hierarchy_resolve, image_resolve ) {
    // Build lookup maps from the full working set
    def byId = objects.collectEntries { [(it.getID().toString()): it] }
    def byName = objects.groupBy { it.getName() }
    
    def findParent = { obj ->
        def md = obj.getMetadata()
        def parent = null
        
        def parentID = md.get( "Parent_ID" )
        if (parentID) {
            parent = byId[ parentID ]
        }
        
        if (!parent) {
            def parentName = md.get( "Parent_Name" )
            if (parentName) {
                parent = byName[ parentName ]?.find()
            }
        }
        
        return parent
    }
    
    // Resolve in dependency order so parents are handled before children
    def remaining = new LinkedHashSet( objects )
    
    // List of any annotations that should have parents that the parent wasn't found
    def missingParents = []
    
    while (!remaining.isEmpty()) {
        def remainingIds = remaining.collect { it.getID().toString() } as Set
        
        def ready = remaining.findAll { obj ->
            def parentID = obj.getMetadata()?.get( "Parent_ID" )
            !parentID || !(parentID in remainingIds)
        }
        
        if (ready.isEmpty()) {
            ready = [remaining.first()]   // safety for bad metadata / cycles
        }
        
        ready.each { obj ->
            def md = obj.getMetadata()
            def parent = findParent( obj )
            
            def expectedParentID = md?.get( "Parent_ID" )
            def expectedParentName = md?.get( "Parent_Name" )
            
            if (parent) {
                hierarchy_resolve.addObjectBelowParent( parent, obj, false )
            }
            
            // Collect missing parent info (but don't stop them from adding)
            if (!parent && (expectedParentID || expectedParentName)) {
                missingParents << [
                    name: obj.getName(),
                    id: obj.getID(),
                    parentID: expectedParentID,
                    parentName: expectedParentName
                ]
            }
            
            remaining.remove( obj )
        }
    }
    
    // If any objects' parent were not found and thus not correctly nested, send warning to logger
    if (!missingParents.isEmpty()) {
        
        String imageName = image_resolve.getImageName()
        
        def loggerWarnMessage = new StringBuilder()
        loggerWarnMessage << "Hierarchy warning: ${imageName}\n\n"
        
        loggerWarnMessage << "Parent could not be found for ${missingParents.size()} objects and could not be nested:\n\n"
        
        missingParents.each {
            loggerWarnMessage << " - Object: [Name = ${it.name}, ID = ${it.id}] " +
                       " Parent: [Name = ${it.parentName}, ID = ${it.parentID}]\n"
        }
        
        logger.warn( loggerWarnMessage.toString() )
        
        return true
    }
    
    return false
}

// Function: Streaming read of the classification names present in a geojson file.
// Skips geometry (the bulk of the file), so it stays cheap even for very large annotation files.
// Defined as a method (not a closure) so it is safe to call from worker-pool threads.
def extractGeojsonClassNames( File f ) {
    def names = new LinkedHashSet()
    def reader = null
    try {
        reader = new JsonReader( new InputStreamReader( new FileInputStream( f ), "UTF-8" ) )
        reader.setLenient( true )

        def token = reader.peek()
        if (token == JsonToken.BEGIN_ARRAY) {
            readFeatureArrayClasses( reader, names )
        } else if (token == JsonToken.BEGIN_OBJECT) {
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "features" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                    readFeatureArrayClasses( reader, names )
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        }
    } catch (Exception ex) {
        logger.warn( "[AnnoTools] duplicate preview parse failed for ${f.getName()}: ${ex.getMessage()}" )
    } finally {
        if (reader != null) { try { reader.close() } catch (Exception ignore) {} }
    }
    return names
}

// Helper for extractGeojsonClassNames: walk a "features" array, pulling out
// properties.classification.name for each feature and skipping everything else.
def readFeatureArrayClasses( JsonReader reader, Set names ) {
    reader.beginArray()
    while (reader.hasNext()) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
        reader.beginObject()
        String className = null
        while (reader.hasNext()) {
            if (reader.nextName() == "properties" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "classification" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() == "name" && reader.peek() == JsonToken.STRING) {
                                className = reader.nextString()
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        names.add( className ?: "Unclassified" )
    }
    reader.endArray()
}

// Function: Create alert selection window for user
def showAlert( Map args ) {
    return runOnFxThreadAndWait {
        def alert = new Alert( args.type ?: Alert.AlertType.CONFIRMATION )

        alert.setTitle( args.title ?: "" )
        alert.setHeaderText( args.header ?: null )

        // Optional "do not ask again" checkbox — its state is written back to the
        // caller's AtomicBoolean holder after the dialog closes so a per-image choice
        // can be remembered and applied to the remaining images
        def dontAskCB = (args.dontAskAgain instanceof AtomicBoolean) ?
            new CheckBox( args.dontAskText ?: "Do not ask again for remaining images" ) : null

        if (dontAskCB != null) {
            def contentBox = new VBox( 10 )
            contentBox.setPadding( new Insets( 5 ) )
            if (args.content) { contentBox.getChildren().add( new Label( args.content ) ) }
            contentBox.getChildren().add( dontAskCB )
            alert.getDialogPane().setContent( contentBox )
        } else {
            alert.setContentText( args.content ?: "" )
        }

        if (args.buttons) {
            alert.getDialogPane().buttonTypes.setAll( args.buttons )
        }

        // Set default button if provided
        if (args.defaultBtn) {
            def node = alert.getDialogPane().lookupButton( args.defaultBtn )
            node?.setDefaultButton( true )
        }

        def result = alert.showAndWait().orElse( args.fallback ?: args.buttons?.find { true } )

        if (dontAskCB != null) { args.dontAskAgain.set( dontAskCB.isSelected() ) }

        return result
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


// Project & preferences file
def project = getProject()
def currentImageData = getCurrentImageData()
def currentEntry = currentImageData ? project.getEntry( currentImageData ) : null
def projectDirectory = project.getBaseDirectory()

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

// Folder and file names of where preferences are stored in directory
def scriptPrefsFolderName = "AnnoTools-Data"
def scriptPrefsFileName   = "Export-Import-Annos_Preferences" + ".json"

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
    dialog.setTitle( "What annotations should be moved?" )
    
    def root = new BorderPane()
    root.setPrefHeight( 350 )
    
    // Defining mode buttons early because other functions rely on their status
    def exportButton = new ToggleButton( " Export" )
    addIcon( exportButton, FontAwesome.Glyph.UPLOAD, 14 )
    def importButton = new ToggleButton( " Import" )
    addIcon( importButton, FontAwesome.Glyph.DOWNLOAD, 14 )
    
    // Stop mode buttons from being untoggled (must click other)
    [exportButton, importButton].each { btn ->
        btn.setFocusTraversable( false )
        btn.addEventFilter( MouseEvent.MOUSE_PRESSED ) {
            if (btn.isSelected()) {
                it.consume()
            }
        }
    }
    
    // Creating group for mode buttons
    def modeButtons = new ToggleGroup()
    exportButton.setToggleGroup( modeButtons )
    importButton.setToggleGroup( modeButtons )
    
    // Setting style for mode buttons
    def commonStyle =
    "-fx-font-size: 13px;" +
    "-fx-padding: 8 15 8 15;"
    
    def selectedStyle =
        commonStyle +
        "-fx-border-color: black;" +
        "-fx-border-width: 1;" +
        "-fx-font-weight: bold;" +
        "-fx-background-color: derive(-fx-base, -30%);"
    
    def unselectedStyle =
        commonStyle +
        "-fx-opacity: 0.6;"
    
    def updateModeButtonStyles = {
        exportButton.setStyle(
            exportButton.isSelected() ? selectedStyle : unselectedStyle
        )
        importButton.setStyle(
            importButton.isSelected() ? selectedStyle : unselectedStyle
        )
    }
    
    modeButtons.selectedToggleProperty().addListener { obs, oldVal, newVal ->
        updateModeButtonStyles()
    }
    
    updateModeButtonStyles()
    
    // Restore previous mode or default to Export
    prePrefsMap.mode == "import" ? modeButtons.selectToggle( importButton ) : modeButtons.selectToggle( exportButton )
    
    
    // BOTTOM: Ok, cancel, and resolve hierarchy buttons
    def resolveHierarchyBtnType = new ButtonType( " Nest existing objects", ButtonBar.ButtonData.LEFT )
    
    dialog.getDialogPane().buttonTypes.setAll( ButtonType.OK, ButtonType.CANCEL, resolveHierarchyBtnType )
    def okBtn = dialog.getDialogPane().lookupButton( ButtonType.OK )
    def resolveHierarchyBtn = dialog.getDialogPane().lookupButton( resolveHierarchyBtnType )
    addIcon( resolveHierarchyBtn, FontAwesome.Glyph.SIGN_IN, 14 )
    ButtonBar.setButtonUniformSize( okBtn, false )
    
    setTT( resolveHierarchyBtn, "Fix hierarchy of existing objects (no importing/exporting)" )
    ButtonBar.setButtonUniformSize( resolveHierarchyBtn, false )
    
    
    
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
    viewersByEntryId.each { entryId, viewers ->
        def liveData = viewers.collect { it.getImageData() }.find {
            it != null && project.getEntry( it )?.getID() == entryId
        }
        if (liveData == null) { return }
        def liveCounts = [:]
        liveData.getHierarchy().getAnnotationObjects().each { obj ->
            def cn = classLabelForObject( obj )
            liveCounts[ cn ] = (liveCounts[ cn ] ?: 0) + 1
        }
        imageClassCounts[ project.getEntry( liveData ).getImageName() ] = liveCounts
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
    
    
    
    // TOP: Folder chooser + Export/Import mode + Include Parents
    def folderRow = new HBox( 10 )
    def folderTitleLabel = new Label( " Folder:" )
    addIcon( folderTitleLabel, FontAwesome.Glyph.FOLDER, 14 )
    
    // Closure: Get width of text in popup
    def getTextWidth = { String text_width, Font font_width ->
        def temp = new Text( text_width )
        temp.setFont( font_width )
        return temp.getLayoutBounds().getWidth()
    }
    
    // Closure: Make the selected folder path viewable
    def formatFolderPathView = { String path_format, double maxWidth, Font font_format ->
        if (!path_format) { return "No folder selected" }
        def reversePathSplit = path_format.tokenize( "/\\\\" ).reverse()
        def viewPath = reversePathSplit[ 0 ]
        for (folder in reversePathSplit.drop( 1 )) {
            def extendedViewPath = folder + "  ▶  " + viewPath
            
            if (getTextWidth( extendedViewPath, font_format ) > (maxWidth * 0.85)) {
                return "...  ▶  " + viewPath
            }
            viewPath = extendedViewPath
        }
        return viewPath
    }
    
    def folderPathLabel = new Label()
    folderPathLabel.setMaxWidth( Double.MAX_VALUE )
    HBox.setHgrow( folderPathLabel, Priority.ALWAYS )
    
    // Formatting/updating folder path label
    boolean widthUpdated = false
    double folderPathLabelWidth = 0
    def folderPathLabelFont = folderPathLabel.getFont()
    folderPathLabel.widthProperty().addListener { obs, oldVal, newVal ->
        if (!widthUpdated) {
            folderPathLabelWidth = folderPathLabel.getWidth()
            widthUpdated = true
            
            folderPathLabel.setText( formatFolderPathView( prePrefsMap.selectedFolder ?: null, folderPathLabelWidth, folderPathLabelFont ) )
            setTT( folderPathLabel, prePrefsMap.selectedFolder ?: "No folder selected" )
            folderPathLabel.setStyle( prePrefsMap.selectedFolder ? "-fx-opacity: 0.8;" : "-fx-opacity: 0.5;" )
        }
    }
    
    def folderStatusRow   = new HBox( 10 )
    folderStatusRow.setPadding( new Insets( 0, 0, 15, 0 ) ) // small bottom gap before mode
    def folderStatusTitle = new Label( " Folder check:" )
    addIcon( folderStatusTitle, FontAwesome.Glyph.SEARCH, 14 )
    def folderStatusLabel = new Label( "" )
    folderStatusRow.getChildren().addAll( folderStatusTitle, folderStatusLabel )
    folderStatusLabel.setStyle( "-fx-opacity: 0.8;" )
    
    // Closure: Count number of geoJson files in a folder path
    def countGeoJsonFiles = { String folderPath ->
        if (!folderPath) return 0
        def folder = new File( folderPath )
        if (!folder.exists() || !folder.isDirectory()) return 0
        return folder.listFiles()?.count { f ->
            f.isFile() && f.name.toLowerCase().endsWith( ".geojson" )
        } ?: 0
    }
    
    // Closure: Get all geoJson file names from target folder
    List geojsonFileNames = []
    Map CbFileMatches = [:]
    def getGeojsonFileNames = { String folderPath ->
        if (!folderPath) { return [] }
        
        def folder = new File( folderPath )    
        List geojsonFiles = folder.listFiles().findAll {
            it.isFile() && it.getName().toLowerCase().endsWith( ".geojson" )
        }
        
        return geojsonFiles.collect { it.getName().replace( ".geojson", "" ) }
    }
    
    // Closure: Find matching text between list and selected CB names
    def getMatchingFiles = { List fileNames, CBs ->
        List selectedCbNames = CBs.findAll { it.isSelected() }.collect { it.getText() }     
        List matchingFileNames = fileNames.findAll { it in selectedCbNames }
        List unmatchingFileNames = selectedCbNames.findAll { it !in fileNames }
        
        return [unmatchingFileNames: unmatchingFileNames, matchingFileNames: matchingFileNames]
    }
    
    // Closure: Change folder status label formatting
    def updateFolderStatus = { String folderPath, Map matchInfo ->
        def setStatus = { String text, String style, String tooltip, FontAwesome.Glyph icon = null ->
            folderStatusLabel.setText( text )
            folderStatusLabel.setStyle( style )
            setTT( folderStatusLabel, tooltip )
            if (icon) { addIcon( folderStatusTitle, icon, 14 ) }
        }
    
        def dim     = "-fx-opacity: 0.5;"
        def green   = "-fx-text-fill: green;"
        def red     = "-fx-text-fill: red;"
        def neutral = "Specifics of issues with files in folder will appear here."
    
        if (!folderPath) {
            setStatus( text    = "No folder selected",
                       style   = dim,
                       tooltip = neutral )
            return
        }
    
        List matches    = matchInfo.matchingFileNames
        List nonMatches = matchInfo.unmatchingFileNames
    
        if (matches.isEmpty() && nonMatches.isEmpty()) {
            setStatus( text    = "No images selected",
                       style   = dim,
                       tooltip = neutral,
                       icon    = FontAwesome.Glyph.SEARCH )
            return
        }
    
        boolean exportMode = exportButton.isSelected()
    
        if (exportMode) {
            if (matches.isEmpty()) {
                setStatus( text    = "Found no files matching selected images.",
                           style   = green,
                           tooltip = "No issues with folder.",
                           icon    = FontAwesome.Glyph.THUMBS_UP )
            } else {
                setStatus( text    = "Found (${matches.size()}) existing files matching selected images.",
                           style   = red,
                           tooltip = "Image files with matching names in selected folder: \n\n- ${matches.join('\n- ')}",
                           icon    = FontAwesome.Glyph.THUMBS_DOWN )
            }
        } else {
            if (nonMatches.isEmpty()) {
                setStatus( text    = "Found files matching all selected images.",
                           style   = green,
                           tooltip = "No issues with folder.",
                           icon    = FontAwesome.Glyph.THUMBS_UP )
            } else if (matches.isEmpty()) {
                setStatus( text    = "Could not find any files matching selected images.", 
                           style   = red, 
                           tooltip = "No image files found.", 
                           icon    = FontAwesome.Glyph.THUMBS_DOWN )
            } else {
                setStatus( text    = "Could not find (${nonMatches.size()}) files matching selected images.",
                           style   = red,
                           tooltip = "Image files that could not be found in selected folder: \n\n- ${nonMatches.join('\n- ')}",
                           icon    = FontAwesome.Glyph.THUMBS_DOWN )
            }
        }
    }
    
    def browseButton = new Button( " Browse" )
    addIcon( browseButton, FontAwesome.Glyph.FOLDER_OPEN_ALT, 14 )
    setTT( browseButton, "Select folder" )
    def selectedFolderPath = prePrefsMap.selectedFolder ?: null
    
    // Making a binding to stop from importing from an empty folder
    def hasGeoJsonFiles = Bindings.createBooleanBinding(
        { if (!selectedFolderPath) return false
          return countGeoJsonFiles( selectedFolderPath ) > 0 }
    )
    
    // Allowing a folder to be browsed for and making updates after
    browseButton.setOnAction {
        def chooser = new DirectoryChooser()
        chooser.setTitle( "Choose folder" )

        if (selectedFolderPath) {
            def prevDir = new File( selectedFolderPath )
            if (prevDir.exists() && prevDir.isDirectory()) {
                chooser.setInitialDirectory( prevDir )
            }
        } else {
            chooser.setInitialDirectory( projectDirectory )
        }
    
        def owner  = dialog.getDialogPane().getScene()?.getWindow()
        def chosen = chooser.showDialog( owner )
        if (chosen != null) {
            selectedFolderPath = chosen.getAbsolutePath()
            hasGeoJsonFiles.invalidate()
            folderPathLabel.setText( formatFolderPathView( selectedFolderPath, folderPathLabelWidth, folderPathLabelFont ) )
            setTT( folderPathLabel, selectedFolderPath )
            geojsonFileNames = getGeojsonFileNames( selectedFolderPath )
            matchInfo = getMatchingFiles( geojsonFileNames, imagesBox.CBs )
            updateFolderStatus( selectedFolderPath, matchInfo )
            folderPathLabel.setStyle( "-fx-opacity: 0.8;" )
        }
    }
    
    imagesBox.CBs.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal ->
            matchInfo = getMatchingFiles( geojsonFileNames, imagesBox.CBs )
            updateFolderStatus( selectedFolderPath, matchInfo )
        }
    }
    
    geojsonFileNames = getGeojsonFileNames( selectedFolderPath )
    matchInfo = getMatchingFiles( geojsonFileNames, imagesBox.CBs )
    updateFolderStatus( selectedFolderPath, matchInfo )
    
    folderRow.getChildren().addAll( folderTitleLabel, folderPathLabel, browseButton )
    
    
    
    // SECOND TO TOP ROW: mode, remove objects, include parents buttons
    // Setting the text on the confirm button to which mode is pre-selected (import vs export)
    boolean exportPreSelected = exportButton.isSelected()
    okBtn.setText( exportPreSelected ? " Export" : " Import" )
    addIcon( okBtn, exportPreSelected ? FontAwesome.Glyph.UPLOAD : FontAwesome.Glyph.DOWNLOAD, 14 )
    
    // Spacer pushes Include Parents to far right
    def modeSpacer = new Region()
    HBox.setHgrow( modeSpacer, Priority.ALWAYS )
    
    // Include Parents checkbox
    def includeParentsCheck = new CheckBox( "Include Parents" )
    includeParentsCheck.setSelected( true ) // Default to true
    
    // Initial tooltip hint
    def exportParentsTTtext = "Add parent info to objects' metadata before export (so hierarchy can be restored when re-imported)"
    def importParentsTTtext = "Use saved parent info in metadata to restore hierarchy (only if exported from here)"
    setTT( includeParentsCheck, exportButton.isSelected() ? exportParentsTTtext : importParentsTTtext )
    
    if (prePrefsMap.mode == "import" || importButton.isSelected()) {
        modeButtons.selectToggle( importButton )
        pathClassBox.box.setVisible( false )
    }
    
    // Updates when mode is switched between import and export
    modeButtons.selectedToggleProperty().addListener { obs, oldToggle, newToggle ->
        boolean exportSelected = (newToggle == exportButton)
        
        okBtn.setText( exportSelected ? " Export" : " Import" )  
        addIcon( okBtn, exportSelected ? FontAwesome.Glyph.UPLOAD : FontAwesome.Glyph.DOWNLOAD, 14 )    
        
        pathClassBox.box.setVisible( exportSelected )     
        setTT( includeParentsCheck, exportSelected ? exportParentsTTtext : importParentsTTtext )  
        updateFolderStatus( selectedFolderPath, matchInfo )
    }
    
    // Creating row and adding children
    def secondRow = new HBox( 10 )
    secondRow.setPadding( new Insets( 0, 0, 10, 0 ) )
    secondRow.getChildren().addAll(
        exportButton,
        importButton,
        modeSpacer,
        includeParentsCheck
    )
    
    // Creating container for both top rows and adding children
    def topContainer = new VBox( 8 )
    topContainer.setPadding( new Insets( 10 ) )
    def folderBox = new VBox( 2 )
    folderBox.getChildren().addAll( folderRow, folderStatusRow )  
    topContainer.getChildren().addAll( folderBox, secondRow )
    
    root.top = topContainer
    
    
    
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
        folderPathLabel.textProperty().isEqualTo( "No folder selected" )
        .or( anySelectedImage.not() )
        .or( exportButton.selectedProperty()
                .and( anySelectedPathClass.not() ) )
        .or( importButton.selectedProperty()
                .and( hasGeoJsonFiles.not() ) )
    )

    // Red outline on each input while it is a reason the "OK" button is disabled
    flagWhenBlocking( folderRow,
        folderPathLabel.textProperty().isEqualTo( "No folder selected" )
        .or( importButton.selectedProperty().and( hasGeoJsonFiles.not() ) ) )
    flagWhenBlocking( imagesBox.box, anySelectedImage.not() )
    flagWhenBlocking( pathClassBox.box,
        exportButton.selectedProperty().and( anySelectedPathClass.not() ) )

    // Disabling the "Nest existing objects" button if no images are selected
    resolveHierarchyBtn.disableProperty().bind(
        anySelectedImage.not()
    )
    
    
    
    // Attach root & show dialog
    dialog.getDialogPane().content = root
    
    def dlgResult = dialog.showAndWait()
    if (dlgResult.isPresent()) {
        def button = dlgResult.get()
        
        if (button in [ButtonType.OK, resolveHierarchyBtnType]) {                
            def selectedImages = imagesBox.CBs
                    .findAll { it.isSelected() }
                    .collect { it.getText() }
                    
            def selectedPathClasses = pathClassBox.CBs
                    .findAll { it.isSelected() }
                    .collect { it.getUserData() }
                    
            // Determine mode from selected toggle buttons
            def selectedModeBtn = modeButtons.getSelectedToggle()
            def mode = ((button == resolveHierarchyBtnType) ? "resolveHierarchy" : (selectedModeBtn == exportButton) ? "export" : "import")
            
            // Return all preferences of "OK" is selected
            return [
                selectedFolder: selectedFolderPath?.toString(),
                mode          : mode,
                includeParents: includeParentsCheck.isSelected(),
                selectedImages: selectedImages,
                currentImage  : currentImageCB.isSelected(),
                objectClasses : selectedPathClasses,
                // Pass the pre-run per-image existing-class snapshot through so the import
                // duplicate dialog can show a project-wide breakdown
                imageClassCounts: imageClassCounts
            ]
            
        } else {
            return null
        }
    }
}


// Exit if cancelling
if (codePreferences == null) {
    return
    
} else if (codePreferences.mode != "resolveHierarchy") { // Write to .json if exporting/importing
    def currentPrefs = [
        selectedFolder: codePreferences.selectedFolder ?: "",
        mode          : codePreferences.mode ?: "export",
        selectedImages: codePreferences.selectedImages ?: [],
        currentImage  : codePreferences.currentImage ?: false,
        objectClasses : codePreferences.objectClasses ?: []
    ]
    
    def outputGson = new GsonBuilder().setPrettyPrinting().create()
    prefsFile.text = outputGson.toJson( currentPrefs )
}


def imagesToRun = project.getImageList().findAll { it.getImageName() in codePreferences.selectedImages }

def total = imagesToRun.size()
def progressUI = createProgressWindow(
    (codePreferences.mode == "resolveHierarchy") ? "Nesting objects..." : 
    (codePreferences.mode == "export") ? "Exporting..." : 
    "Importing...", total, imagesToRun.collect { it.getImageName() })
def stopLaterFlag = progressUI.stopLaterFlag
int processed = 0

// Checks on removing current objs
boolean removeObjs = false
boolean confirmRemoveObjsChoice = false
boolean skipDupObjs = false   // import: skip every image that has duplicates, leaving it untouched
def skipDupImageNames = null  // names of those duplicate images, taken from the project-wide preview
def skippedDupImages = []     // names actually passed over, reported once the run finishes

// Remembered per-image prompt choices (set when the user ticks "do not ask again")
String  fileExistsAction = null   // export, file exists:    "skip" | "overwrite"
String  noObjectsAction  = null   // export, no objects:     "skip" | "exportEmpty"
boolean skipMissingFiles = false  // import, file not found:  skip all remaining

// Flag to show warning
boolean anyNestingProblems = false

// Image being worked on, so the handler below can name it when a run dies — "stopped after
// an error" is useless when the run covered a hundred images
String imageInFlight = null

try {
imageLoop:
for (image in imagesToRun) {
    // Stopping when requested
    if (stopLaterFlag.get()) { break }

    imageInFlight = image.getImageName()

    boolean skipImage = false
    boolean nestingProblem = false
    def imageName = image.getImageName()

    // Image data is read lazily: an image that ends up skipped (nothing to export, no import file,
    // or a duplicate in "Skip duplicates" mode) never pays for reading — or writing back — its
    // data, which is what made skipping feel as slow as importing.
    def imageData = null
    def hierarchy = null
    boolean dataChanged = false
    
    // Viewers showing this image right now — a pane the user re-pointed mid-run no longer counts
    def openViewers = ( viewersByEntryId[ image.getID() ] ?: [] ).findAll { viewer ->
        def viewerData = viewer.getImageData()
        viewerData != null && project.getEntry( viewerData )?.getID() == image.getID()
    }
    
    def loadData = {
        if (imageData == null) {
            // Editing the data a viewer already holds, so that pane stays in step with the run
            imageData = openViewers ? openViewers[ 0 ].getImageData() : image.readImageData()
            hierarchy = imageData.getHierarchy()
        }
    }
    
    
    // Reporting current image
    updateProgressWindow( progressUI, [imageName: image] )
    
    
    // --- Execution ---
    def filePath = buildFilePath( codePreferences.selectedFolder, imageName + ".geojson" )
    def folder = new File( codePreferences.selectedFolder )
    def file = new File( filePath )
    
    
    // Fixing hierarchy if desired
    if (codePreferences.mode == "resolveHierarchy") {
        loadData()
        def objectsToresolve = hierarchy.getAnnotationObjects()
        anyNestingProblems = resolveHierarchy( objectsToresolve, hierarchy, image )
        dataChanged = true
        
    } else if (codePreferences.mode == "export") {
        
        // If file exists, giving warning and option to skip/overwrite/exit
        if (file.exists()) {

            // Use the remembered choice if the user previously opted to apply it to all
            String action = fileExistsAction

            if (action == null) {
                def cancelScriptBtn = new ButtonType( "Exit", ButtonBar.ButtonData.CANCEL_CLOSE )
                def skipFileBtn     = new ButtonType( "Skip image", ButtonBar.ButtonData.HELP )
                def okWarningBtn    = new ButtonType( "Overwrite", ButtonBar.ButtonData.HELP_2 )

                def dontAsk = new AtomicBoolean( false )
                def existingFileDecision = showAlert(
                    title: "File already exists",
                    header: imageName,
                    buttons: [cancelScriptBtn, skipFileBtn, okWarningBtn],
                    defaultBtn: skipFileBtn,
                    fallback: cancelScriptBtn,
                    dontAskAgain: dontAsk,
                    dontAskText: "Apply this choice to all remaining existing files"
                )

                // Handling user decision logic
                switch (existingFileDecision) {
                    case (cancelScriptBtn):
                        break imageLoop
                    case (skipFileBtn):
                        action = "skip"
                        break
                    case (okWarningBtn): // Overwrite and continue script
                        action = "overwrite"
                        break
                }

                // Remember the choice for the rest of the run if requested
                if (dontAsk.get()) { fileExistsAction = action }
            }

            if (action == "skip") { skipImage = true }
            // "overwrite" continues without skipping
        }
        
        // --- Filter annotations (only for images that are actually being exported) ---
        if (!skipImage) { loadData() }
        def annotationsForExport = skipImage ? [] : hierarchy.getAnnotationObjects().findAll {
            def matchClass = classLabelForObject( it ) in codePreferences.objectClasses
            return matchClass
        }
        
        // --- Add parent metadata if enabled ---
        def finalObjects = codePreferences.includeParents ? annotationsForExport.collect { obj ->
            def parent = obj.getParent()
            if (parent && !parent.isRootObject()) { 
                obj.getMetadata().put( "Parent_ID",   parent.getID().toString() )
                obj.getMetadata().put( "Parent_Name", parent.getName() )
            }
            return obj
        } : annotationsForExport
        
        // --- Option to skip image if no objects to export ---
        if (finalObjects.isEmpty() && !skipImage) {

            // Use the remembered choice if the user previously opted to apply it to all
            String action = noObjectsAction

            if (action == null) {
                def exitBtn        = new ButtonType( "Exit", ButtonData.CANCEL_CLOSE )
                def skipBtn        = new ButtonType( "Skip image", ButtonData.HELP )
                def exportEmptyBtn = new ButtonType( "Export empty file", ButtonData.HELP )

                def dontAsk = new AtomicBoolean( false )
                def exportOrSkip = showAlert(
                    title: "No objects to export",
                    header: imageName,
                    buttons: [exitBtn, skipBtn, exportEmptyBtn],
                    defaultBtn: exportEmptyBtn,
                    fallback: exitBtn,
                    dontAskAgain: dontAsk,
                    dontAskText: "Apply this choice to all remaining images with no objects"
                )

                // Handling user decision logic
                switch (exportOrSkip) {
                    case (exitBtn):
                        break imageLoop
                    case (skipBtn):
                        action = "skip"
                        break
                    case (exportEmptyBtn):
                        action = "exportEmpty"
                        break
                }

                // Remember the choice for the rest of the run if requested
                if (dontAsk.get()) { noObjectsAction = action }
            }

            if (action == "skip") { skipImage = true }
            // "exportEmpty" leaves skipImage false so the empty file is written
        }
        
        // --- Exporting if objects present ---
        if (!skipImage) {
            exportObjectsToGeoJson( finalObjects, filePath, "FEATURE_COLLECTION" )
        }
        
        
    } else if (codePreferences.mode == "import") {
        
        // If cannot locate file, asking if user wants to manually locate
        if (!file.exists() && skipMissingFiles) {
            // User previously opted to skip all files that can't be found
            skipImage = true

        } else if (!file.exists()) {

            searchLoop:
            while (true) {

                def fileCancelBtn = new ButtonType( "Cancel", ButtonData.CANCEL_CLOSE )
                def skipBtn       = new ButtonType( "Skip", ButtonData.HELP_2 )
                def browseBtn     = new ButtonType( "Browse...", ButtonData.HELP )

                def dontAsk = new AtomicBoolean( false )
                def findFilePreference = showAlert(
                    title: "File not found",
                    header: imageName,
                    content: "Locate file?",
                    buttons: [fileCancelBtn, skipBtn, browseBtn],
                    defaultBtn: browseBtn,
                    fallback: fileCancelBtn,
                    dontAskAgain: dontAsk,
                    dontAskText: "Skip all remaining files that can't be found"
                )

                // Handling user decision logic
                switch (findFilePreference) {

                    case (fileCancelBtn):
                        break imageLoop

                    case (skipBtn):
                        skipImage = true
                        // Remember to skip all remaining missing files if requested
                        if (dontAsk.get()) { skipMissingFiles = true }
                        break searchLoop

                    case (browseBtn):
                        def selectedFile = runOnFxThreadAndWait {
                            
                            // user clicked Browse..., open file chooser
                            def folderPath  = new File( codePreferences.selectedFolder )
                            def fileChooser = new FileChooser()
                            fileChooser.setTitle( "Find: ${imageName}" )
                            fileChooser.getExtensionFilters().add(
                                new FileChooser.ExtensionFilter( "GeoJSON files", "*.geojson" )
                            )
                            fileChooser.setInitialDirectory( folderPath )
                            
                            def owner      = QuPathGUI.getInstance().getStage()
                            def fileChoice = fileChooser.showOpenDialog( owner )
                            
                            return fileChoice
                        }
                        
                        if (selectedFile instanceof File) {
                            file = selectedFile
                            break searchLoop
                        }
                }
            }
        }
        
        
        // In "Skip duplicates" mode the duplicate images are already known from the project-wide
        // preview, so they are passed over here without their image data ever being read
        if (!skipImage && skipDupObjs && skipDupImageNames?.contains( imageName )) {
            skipImage = true
            skippedDupImages << imageName
        }

        // -- Importing objects if image is not being skipped --
        if (!skipImage) {

            loadData()

            // Local snapshot of what already exists in the image
            def workingObjects = hierarchy.getAnnotationObjects().toList()
            
            // Read imported objects
            def importedObjects = []
            try {
                importedObjects = PathIO.readObjects( file )
            } catch (Exception e) {
                Dialogs.showErrorNotification( "Skipping import for '${file.getName()}'", e.getMessage() )
                logger.error( "Skipping import for '${file.getName()}': ${e.getMessage()}" )
            }
            
            // Optionally remove current objects of the same class
            def importingObjClasses = importedObjects*.getPathClass().unique()
            def existingObjsSameClasses = hierarchy.getAnnotationObjects().findAll {
                it.getPathClass() in importingObjClasses
            }
            
            // If objects of same class as imported ones exist, prompt to remove or keep once
            boolean skipDupThisImage = false
            if (!existingObjsSameClasses.isEmpty()) {
                    
                if (!confirmRemoveObjsChoice) {
                    
                    def exitBtn   = new ButtonType( "Exit", ButtonData.CANCEL_CLOSE )
                    def keepBtn   = new ButtonType( "Keep", ButtonData.HELP )
                    def removeBtn = new ButtonType( "Remove", ButtonData.HELP )
                    def skipDupBtn = new ButtonType( "Skip duplicates", ButtonData.HELP )
                    
                    // Project-wide preview: which images have existing objects whose class also
                    // appears in that image's import file. Existing classes come from the pre-run
                    // summary.json snapshot captured in the preferences popup (imageClassCounts);
                    // imported class names are read from each geojson in parallel with a streaming
                    // reader that skips geometry, so this stays fast even for large annotation files.
                    def existingCountsByImage = codePreferences.imageClassCounts ?: [:]

                    // Only images that both have existing objects and an import file on disk can
                    // possibly produce a duplicate — skip reading anything else.
                    def dupCandidates = imagesToRun.findAll { img ->
                        def existing = existingCountsByImage[ img.getImageName() ]
                        existing && !existing.isEmpty() &&
                            new File( buildFilePath( codePreferences.selectedFolder, img.getImageName() + ".geojson" ) ).exists()
                    }

                    // imageName -> (className -> existing count), kept only where overlap is non-empty
                    def dupBreakdown = new LinkedHashMap()
                    if (!dupCandidates.isEmpty()) {
                        def dupThreads = Math.min( Runtime.getRuntime().availableProcessors(), dupCandidates.size() )
                        def dupPool    = Executors.newFixedThreadPool( dupThreads )
                        try {
                            def dupFutures = dupCandidates.collect { img ->
                                def imgName = img.getImageName()
                                def geojson = new File( buildFilePath( codePreferences.selectedFolder, imgName + ".geojson" ) )
                                dupPool.submit( { [imgName, extractGeojsonClassNames( geojson )] } as Callable )
                            }
                            dupFutures.each { fut ->
                                def (imgName, importedNames) = fut.get()
                                def existing = existingCountsByImage[ imgName ] ?: [:]
                                def overlap  = new LinkedHashMap()
                                existing.each { cn, cnt -> if (cn in importedNames) { overlap[ cn ] = cnt } }
                                if (!overlap.isEmpty()) { dupBreakdown[ imgName ] = overlap }
                            }
                        } finally {
                            dupPool.shutdown()
                        }
                    }

                    // Always represent the current (triggering) image from its real overlap, in case
                    // its summary.json snapshot was stale or missing. Also guarantees a non-empty list.
                    if (!dupBreakdown.containsKey( imageName )) {
                        def overlap = new LinkedHashMap()
                        existingObjsSameClasses.each { o ->
                            def cn = classLabelForObject( o )
                            overlap[ cn ] = (overlap[ cn ] ?: 0) + 1
                        }
                        if (!overlap.isEmpty()) { dupBreakdown[ imageName ] = overlap }
                    }

                    int affectedImages  = dupBreakdown.size()
                    int affectedClasses = dupBreakdown.values().collect { it.keySet() }.flatten().unique().size()
                    
                    def confirmRemoveObjs = runOnFxThreadAndWait {
                        def alert = new Alert( Alert.AlertType.CONFIRMATION )
                        alert.setTitle( "Potential duplicate objects!" )
                        alert.setHeaderText(
                            "Existing objects share classes with the import.\n" +
                            "Choose how to handle the images that already contain these classes:\n" +
                            "  • Keep — import anyway, alongside the existing objects\n" +
                            "  • Remove — delete the existing objects, then import\n" +
                            "  • Skip duplicates — import only into images without duplicates\n" +
                            "WARNING: Removed objects and their children cannot be recovered."
                        )
                        alert.getDialogPane().buttonTypes.setAll( exitBtn, keepBtn, removeBtn, skipDupBtn )
                        alert.getDialogPane().lookupButton( removeBtn )?.setDefaultButton( true )

                        // Expandable tree: a collapsible root holds every image; each image in turn
                        // expands to its overlapping classes (+ existing counts)
                        def treeRoot = new TreeItem( "Images with potential duplicates [${affectedImages}]".toString() )
                        treeRoot.setExpanded( false )
                        dupBreakdown.each { imgName, classes ->
                            int imgTotal = (classes.values().sum() ?: 0)
                            def imgItem = new TreeItem( "${imgName}  —  ${classes.size()} class(es)" )
                            imgItem.setExpanded( dupBreakdown.size() == 1 )
                            classes.sort { a, b -> (b.value <=> a.value) ?: (a.key <=> b.key) }.each { cn, cnt ->
                                imgItem.getChildren().add( new TreeItem( "${cn} [${cnt} existing]".toString() ) )
                            }
                            treeRoot.getChildren().add( imgItem )
                        }
                        def tree = new TreeView( treeRoot )
                        tree.setShowRoot( true )

                        // Size the tree to its currently-visible rows so the dialog is compact when
                        // collapsed and grows only as images/classes are expanded — capped so that many
                        // images scroll inside the tree rather than producing an oversized window.
                        double dupRowHeight = 24.0
                        int    dupMaxRows   = 12
                        tree.setFixedCellSize( dupRowHeight )
                        tree.setPrefWidth( 480 )
                        def sizeTreeToContent = {
                            int rows  = Math.max( tree.getExpandedItemCount(), 1 )
                            int shown = Math.min( rows, dupMaxRows )
                            double h  = shown * dupRowHeight + 6
                            tree.setMinHeight( h )
                            tree.setPrefHeight( h )
                            tree.setMaxHeight( h )
                        }
                        sizeTreeToContent()
                        // Re-size the tree and shrink/grow the window whenever rows are expanded/collapsed
                        tree.expandedItemCountProperty().addListener { obs, oldV, newV ->
                            sizeTreeToContent()
                            Platform.runLater {
                                def w = tree.getScene()?.getWindow()
                                if (w != null) { w.sizeToScene() }
                            }
                        }

                        def summaryLabel = new Label( "${affectedImages} image(s) with potential duplicates across ${affectedClasses} class(es):".toString() )
                        summaryLabel.setStyle( "-fx-font-weight: bold;" )
                        def dupContent = new VBox( 8, summaryLabel, tree )
                        dupContent.setPadding( new Insets( 5 ) )
                        alert.getDialogPane().setContent( dupContent )
                        alert.setResizable( true )

                        return alert.showAndWait().orElse( exitBtn )
                    }

                    // Handling user decision logic
                    switch (confirmRemoveObjs) {
                        case (exitBtn):
                            break imageLoop
                        case (keepBtn):
                            removeObjs = false
                            confirmRemoveObjsChoice = true
                            break
                        case (removeBtn):
                            removeObjs = true
                            confirmRemoveObjsChoice = true
                            break
                        case (skipDupBtn):
                            skipDupObjs = true
                            confirmRemoveObjsChoice = true
                            // Remember the images the preview flagged, so the rest of the run can
                            // pass over them without reading their image data
                            skipDupImageNames = new HashSet( dupBreakdown.keySet() )
                            break
                    }
                } 
                
                if (removeObjs) {
                    hierarchy.removeObjects( existingObjsSameClasses, false )
                    workingObjects.removeAll( existingObjsSameClasses )
                    dataChanged = true
                }

                // In "skip duplicates" mode, import nothing into images that have duplicates
                skipDupThisImage = skipDupObjs
                if (skipDupThisImage) { skippedDupImages << imageName }
            }
            
            // Skip importing entirely for duplicate images when "Skip duplicates" was chosen
            if (!skipDupThisImage) {
                dataChanged = true

                // Add all imported objects first with no nesting yet
                importedObjects.each { obj ->
                    hierarchy.addObject( obj, false )
                    obj.setLocked( true )
                    workingObjects << obj
                }

                // Resolving hierarchy if desired
                if (codePreferences.includeParents) {
                    anyNestingProblems = resolveHierarchy( workingObjects, hierarchy, image )
                }
            }
        }
    }
    
    
    // Updating viewer and saving (when relevant) — only images whose data was actually read and
    // changed are written back; skipped images are left completely untouched
    if (imageData != null && codePreferences.mode in ["resolveHierarchy", "import"]) {
        if (dataChanged) {
            // Saving data
            image.saveImageData( imageData )
            
            // Ensure every viewer showing this image updates — including split panes that aren't
            // selected, which otherwise keep drawing the objects they had before the run
            if (openViewers) {
                runOnFxThreadAndWait {
                    imageData.getHierarchy().fireHierarchyChangedEvent( this )
                    openViewers.each { it.repaintEntireImage() }
                }
            }
        }
        
        // Closing background imagedata — data a viewer holds stays open, it isn't ours to close
        if (!openViewers) {
            imageData.close()
        }
    }
    
    
    // - Reporting progress - 
    processed++
    double frac = processed / total
    updateProgressWindow( progressUI, [imagePer: frac, imageNum: processed] )
    System.sleep( 10 )
}
} catch (Throwable err) {
    // Runs outside the script editor, so a thrown error would be invisible: log it (the full stack
    // trace goes to QuPath's log), point the user there, and close the modal window so QuPath isn't
    // left blocked. Then stop.
    String failedOn = imageInFlight ? " on image '${imageInFlight}'" : ""
    logger.error( "Export / Import stopped after an error" + failedOn, err )
    Dialogs.showErrorNotification( "Export / Import", "Stopped after an error" + failedOn + ". See the log for details." )
    Platform.runLater { progressUI.stage.close() }
    return
}

// Closing progress bar window (runs on the success path)
Platform.runLater {
    progressUI.stage.close()
}

// Reporting how many images were passed over as duplicates
if (!skippedDupImages.isEmpty()) {
    logger.info( "[AnnoTools] Export / Import: skipped ${skippedDupImages.size()} selected image(s) with duplicate class objects: ${skippedDupImages.join( ', ' )}" )
    Dialogs.showInfoNotification(
        "Export / Import",
        "Skipped ${skippedDupImages.size()} selected image(s) with duplicate class objects."
    )
}

// Showing warning message if any nesting problems
if (anyNestingProblems) {
    Dialogs.showWarningNotification(
        "Hierarchy warning!",
        "One or more images had objects that could not be nested.\nCheck the log for details."
    )
}


import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javafx.util.Duration
import javafx.animation.*
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Insets
import javafx.geometry.*
import javafx.scene.Scene
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.layout.*
import javafx.scene.input.*
import javafx.scene.text.Text
import javafx.scene.text.Font
import javafx.stage.*
import javafx.beans.binding.Bindings
import com.google.gson.GsonBuilder
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import qupath.lib.gui.tools.IconFactory
import org.controlsfx.glyphfont.FontAwesome


// Comments for tool launcher icon and tooltip
// ORDER: 1
// ICON: EXCHANGE
// Export or import annotations with parents