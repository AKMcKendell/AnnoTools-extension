/*
 * Author: Alec McKendell
 * Date: 2026.2.4
 * Description: Manually classify many objects.
*/


// --- Classes ---
// Define once at the top of the script
class ViewerSessionVars {
    List<String> classifiers
    List<String> reviewers
    ImageServer  server
    QuPathViewer viewer
    def          view
    def          activeAnimation
    def          alertRightEdge
    String       userName
    String       userKey
    String       classifyingName
    List<String> classifierKeys
    String       toolKeyPrefix
    String       annoClass
    int          gridSize
    boolean      singleObjects // classifier mode: one annotation at a time instead of a grid square of them
    def          hierarchy
    def          allClassAnnos
    boolean      checkingWork
    def          channelToChange
    def          brightnessInfo
    boolean      freeView
    String       freeViewMode
    List         freeViewModes
    def          nonZoomedViewerState
    boolean      instructions
    boolean      zoomed
    Map          annoStateMap
    def          statesAndColors
    def          channelSpecifics
    def          controllableChannels
    def          prevSelectedAnnos
    double       pixelSize
    List<String> reviewStateFreqs
    List<String> reviewStates
    Map          reviewerCriteria
    String       message
    String       lastMessage
    long         lastMessageTime
    String       toggleChannel
    List         toggleChannels
    List<String> reviewerStates
    String       reviewerClassification
    def          prefsFile
    List         imagesToProcess
    def          currentEntry
    Map          frequencies
    Map          currentAnnoColorsMap
    List         highlightStatesAndColorsMap
    int          viewerMoveTimeMS
    boolean      groupDone
    List         hoverAnnos
    List         currentGroup // classifier mode: the annotations being classified right now
    def          hoveredAnno
    String       UI_COMMAND
    
    // Making mode variables that are permanently opposite
    private boolean _classifierMode = false
    
    boolean getClassifierMode() { _classifierMode }
    boolean getReviewerMode()   { !_classifierMode }
    
    void setClassifierMode( boolean val ) {
        _classifierMode = val
    }
    void setReviewerMode( boolean val ) {
        _classifierMode = !val
    }
}

def session = new ViewerSessionVars(
    toolKeyPrefix               : "AT-manual",
    currentAnnoColorsMap        : [:],
    annoStateMap                : [:],
    highlightStatesAndColorsMap : [],
    viewerMoveTimeMS            : 300,
    activeAnimation             : null
)

// Class for actions so they aren't passed as strings
class ActionStrings {
    static final String CLASSIFIER_MODE       = "classifier"
    static final String REVIEWER_MODE         = "reviewer"
    static final String CANCEL                = "Cancel"
    static final String NEXT                  = "Next"
    static final String BACK                  = "Back"
    static final String REMOVE                = "Remove"
    static final String YES                   = "Yes"
    static final String NO                    = "No"
    static final String TOGGLE_FREE_VIEW      = "ToggleFreeView"
    static final String SWITCH_FREE_VIEW_MODE = "SwitchFreeViewMode"
    static final String TOGGLE_ANNOS          = "ToggleAnnos"
    static final String TOGGLE_GRID           = "ToggleGrid"
    static final String TOGGLE_FOCUS          = "ToggleFocus"
    static final String TOGGLE_CLASS          = "ToggleClassification"
    static final String SET_CLASS             = "SetClassification"
    static final String TOGGLE_CHANNEL        = "ToggleChannel"
    static final String BRIGHT_UP             = "BrightUP"
    static final String BRIGHT_DOWN           = "BrightDOWN"
    static final String INSTRUCTIONS          = "Instructions"
    static final String ZOOM                  = "zoom"
    static final String BRIGHTNESS            = "brightness"
    static final String BACK_TO_LAST          = "Back to last"
    static final String REVIEW_ALL            = "Review all"
}

// Class for UI elements to update when needed
class elements {
    VBox        grid
    List        btnMap
    VBox        headerBox
    Map         channelEntryByChanName
    HBox        classesGrid
    HBox        classesSummaryGrid
    HBox        channelGrid
    HBox        revClassGrid
    VBox        classesSection
    VBox        classesSummarySection
    VBox        channelSection
    VBox        revClassSection
    Map         classLabelByClassName
    Map         classLabelSummaryByClassName
    ToggleGroup classesSummaryToggleGroup
    List        classesSummaryBtns
    Map         classesSummaryStyleMap
    Label       lastLabelHighlighted
    Label       savedStatusLabel
    String      lastHighlightStr
}

def uiElements = new elements()

// Class for original viewer settings
class settings {
    AnimationTimer   hoverTimer
    EventHandler     scrollBlocker
    String           scrollFilterKey
    boolean          hoverCheck
    boolean          wasGridOn
    double           oGridX
    double           oGridY
    boolean          wasOverviewOn
    double           oldAnnoThickness
    Map              oldOverlay
    def              overlayOptions
}

def viewerSettings = new settings()


@groovy.transform.Canonical
class ImageCheckResult {
    def image
    String imageName
    boolean hasNoAnnos
    boolean anyMissingClassifierKeys
    boolean anyMissingUserKey
    boolean anyUserKey
    boolean anyAnnosToCheck
    Set<String> missingUsers
    Map<String, Set<String>> incompleteUsersByImage
}


// --- Functions ---

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

// FX-safe helper function
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

// Function to get base channel name with no numbers (e.g. "(C1)")
def baseChannelName( name ) {
    // Untyped parameter and an internal coercion: a typed String parameter blows up with
    // "argument type mismatch" when a closure passes it null (e.g. a channel with no name).
    String chName = name as String
    chName?.replaceAll( '\\s*\\(C\\d+\\)\\s*$', '' )?.trim() ?: chName
}

// Function to set or change a tooltip hint for one or multiple objects
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

// Function to make instructions and add circles of specific color if wanted (add null to make new line)
def makeLines( List<List> rows ) {
    def grid = new GridPane()
    grid.setHgap( 12 )
    grid.setVgap( 5 )
    grid.setPadding( new Insets( 4, 0, 4, 0 ) )

    ColumnConstraints keyCol = new ColumnConstraints()
    keyCol.setHalignment( HPos.RIGHT )
    ColumnConstraints descCol = new ColumnConstraints()
    descCol.setHalignment( HPos.LEFT )
    grid.getColumnConstraints().addAll( keyCol, descCol )

    rows.eachWithIndex { row, rowIdx ->
        if (row.size() == 1) {
            def label = new Label( row[0].toString() )
            label.setStyle( "-fx-font-weight: bold;" )
            GridPane.setColumnSpan( label, GridPane.REMAINING )
            grid.add( label, 0, rowIdx )
        } else {
            def keyLabel = new Label( row[0].toString() )
            keyLabel.setStyle( "-fx-font-weight: bold;" )
            grid.add( keyLabel, 0, rowIdx )

            def descBox = new HBox( 6 )
            descBox.setAlignment( Pos.CENTER_LEFT )
            row.drop(1).each { item ->
                if (item instanceof Color) {
                    def circle = new Circle( 6 )
                    circle.setStroke( item )
                    circle.setStrokeWidth( 2 )
                    circle.setFill( Color.TRANSPARENT )
                    descBox.getChildren().add( circle )
                } else {
                    descBox.getChildren().add( new Label( item.toString() ) )
                }
            }
            grid.add( descBox, 1, rowIdx )
        }
    }
    return grid
}

// Function to build state summary buttons in free view mode
def makeClassesSummaryBtn( Map group, elements uiElements, ViewerSessionVars session ) {
    String stateName = group.state
    String cssColor  = intToFx( group.color ).toString().replace( "0x", "#" )
    
    def standardStateSummaryStyle   = "-fx-border-color: ${cssColor}; -fx-border-width: 1.5; -fx-border-radius: 3;"
    def unselectedStateSummaryStyle = "-fx-opacity: 0.5; -fx-effect: innershadow(gaussian, rgba(0,0,0,1), 8, 0.3, 2, 2);"
    def selectedStateSummaryStyle   = "-fx-border-color: yellow; -fx-border-width: 1.5; -fx-border-radius: 3;"
    
    def stateButton = new ToggleButton( "${stateName}  (${group.count})" )
    stateButton.setPadding( new Insets( 5, 10, 5, 10 ) )
    stateButton.setFocusTraversable( false )
    stateButton.setToggleGroup( uiElements.classesSummaryToggleGroup )
    stateButton.setStyle( standardStateSummaryStyle )
    uiElements.classesSummaryStyleMap[ stateButton ] = standardStateSummaryStyle
    
    stateButton.selectedProperty().addListener { obs, wasSelected, isSelected ->
        Platform.runLater {
            colorAnnosFromMap( session, session.classifierMode ? [session.userKey] : session.classifierKeys )
            def currentGroup = session.highlightStatesAndColorsMap.find { it.state.toString() == stateName }
            if (isSelected) {
                stateButton.setStyle( selectedStateSummaryStyle )
                uiElements.classesSummaryBtns.each { btn ->
                    if (btn !== stateButton) { btn.setStyle( unselectedStateSummaryStyle ) }
                }
                if (currentGroup) {
                    selectObjects( currentGroup.annos )
                    session.allClassAnnos.each { anno ->
                        if (!(anno in currentGroup.annos)) { anno.setColor( ColorTools.makeRGB( 0, 0, 0 ) ) }
                    }
                }
            } else {
                stateButton.setStyle( uiElements.classesSummaryStyleMap[ stateButton ] )
                uiElements.classesSummaryBtns.each { btn ->
                    if (btn !== stateButton) { btn.setStyle( uiElements.classesSummaryStyleMap[ btn ] ) }
                }
                resetSelection()
            }
        }
    }
    
    def entry = [
        name    : "${stateName}  (${group.count})",
        clName  : stateName,
        btn     : stateButton,
        group   : "classesSummary",
        active  : false,
        cssColor: cssColor
    ]
    uiElements.btnMap << entry
    uiElements.classLabelSummaryByClassName[ stateName ] = entry
    uiElements.classesSummaryBtns << stateButton
}

// Function for buttons and response
def buildControlPanel( Alert alert, ViewerSessionVars session, elements uiElements, settings viewerSettings, queue ) {
    uiElements.grid = new VBox( 20 )
    uiElements.grid.setPadding( new Insets( 10 ) )
    uiElements.grid.setAlignment( Pos.CENTER )
    
    uiElements.classesGrid = new HBox( 15 )
    uiElements.classesGrid.setAlignment( Pos.CENTER )
    uiElements.classesGrid.setPadding( new Insets( 6, 8, 6, 8 ) )
    
    uiElements.classesSummaryGrid = new HBox( 15 )
    uiElements.classesSummaryGrid.setAlignment( Pos.CENTER )
    uiElements.classesSummaryGrid.setPadding( new Insets( 6, 8, 6, 8 ) )
    
    uiElements.channelGrid = new HBox( 15 )
    uiElements.channelGrid.setAlignment( Pos.CENTER )
    uiElements.channelGrid.setPadding( new Insets( 6, 8, 6, 8 ) )
    
    uiElements.revClassGrid = new HBox( 15 )
    uiElements.revClassGrid.setAlignment( Pos.CENTER )
    uiElements.revClassGrid.setPadding( new Insets( 6, 8, 6, 8 ) )
    
    uiElements.classesSection = new VBox( 4 )
    uiElements.classesSection.setAlignment( Pos.CENTER )
    
    uiElements.classesSummarySection = new VBox( 4 )
    uiElements.classesSummarySection.setAlignment( Pos.CENTER )
    
    uiElements.channelSection = new VBox( 4 )
    uiElements.channelSection.setAlignment( Pos.CENTER )
    
    uiElements.revClassSection = new VBox( 4 )
    uiElements.revClassSection.setAlignment( Pos.CENTER )
    
    uiElements.grid.getChildren().addAll( uiElements.classesSection, uiElements.classesSummarySection,
                                          uiElements.channelSection, uiElements.revClassSection )
    
    alert.getDialogPane().setContent( uiElements.grid )
    
    
    // -- Creating map of all possible actions and their associated info --
    // Including their button, key shortcut, click, etc.
    uiElements.btnMap = []
    
    uiElements.btnMap << [ name   : ActionStrings.CANCEL,
                           action : { queue.put( ActionStrings.CANCEL ) },
                           btnType: new ButtonType( "(C)ancel", ButtonData.HELP_2 ),
                           key    : ["c", (KeyCode.ESCAPE)] ]
                           
    uiElements.btnMap << [ name   : ActionStrings.INSTRUCTIONS,
                           action : { queue.put( ActionStrings.INSTRUCTIONS ) },
                           key    : "i" ]
                           
    uiElements.btnMap << [ name   : ActionStrings.TOGGLE_ANNOS,
                           action : { queue.put( ActionStrings.TOGGLE_ANNOS ) },
                           key    : "a",
                           click  : MouseButton.MIDDLE ]
                           
    uiElements.btnMap << [ name   : ActionStrings.TOGGLE_GRID,
                           action : { queue.put( ActionStrings.TOGGLE_GRID ) },
                           key    : "g" ]
    
    uiElements.btnMap << [ name   : ActionStrings.TOGGLE_FOCUS,
                           action : { queue.put( ActionStrings.TOGGLE_FOCUS ) },
                           click  : MouseButton.SECONDARY ]
                           
    uiElements.btnMap << [ name   : ActionStrings.REMOVE,
                           action : { queue.put( ActionStrings.REMOVE ) },
                           btnType: new ButtonType( "(R)emove All", ButtonData.HELP_2 ),
                           btnTT  : "Remove current user's subclassifications from ${session.singleObjects ? 'the current annotation' : 'all objects in current group'}",
                           key    : "r" ]
                           
    uiElements.btnMap << [ name   : ActionStrings.BACK,
                           action : { queue.put( ActionStrings.BACK ) },
                           btnType: new ButtonType( "(B)ack", ButtonData.BACK_PREVIOUS ),
                           btnTT  : "Go back to previous ${session.classifierMode && !session.singleObjects ? 'group' : 'annotation'}",
                           key    : "b",
                           click  : MouseButton.BACK ]
                           
    uiElements.btnMap << [ name   : ActionStrings.NEXT,
                           action : { queue.put( ActionStrings.NEXT ) },
                           btnType: new ButtonType( "(N)ext", ButtonData.YES ),
                           btnTT  : "Save current subclassifications and move to next group",
                           key    : "n",
                           click  : MouseButton.FORWARD ]
                           
    uiElements.btnMap << [ name  : ActionStrings.TOGGLE_CLASS,
                           action: { queue.put( ActionStrings.TOGGLE_CLASS ) },
                           click : MouseButton.PRIMARY ]
    
    
    // - Classification labels -
    def classLabel = new Label( "${session.classifyingName} subclassification:" )  
    classLabel.setStyle( "-fx-font-weight: bold;" )
    
    // Says whether the current annotation's (or group's) calls are saved yet, since a saved call in the
    // first state and no call at all are drawn in the same color. Kept up to date by updateControlPanel
    uiElements.savedStatusLabel = new Label()
    uiElements.classesSection.getChildren().addAll( classLabel, uiElements.classesGrid, uiElements.savedStatusLabel )
    
    uiElements.btnMap << [ name  : "Classification label",
                           btn   : classLabel,
                           group : "classes" ]
    
    session.statesAndColors.eachWithIndex { group, idx ->
        String labelText = "${idx+1}. ${group.state}"
        def stateLabel = new Label( labelText )
        stateLabel.setPadding( new Insets( 5, 10, 5, 10 ) )
        uiElements.classesGrid.getChildren().add( stateLabel )
        
        uiElements.btnMap << [
            name  : labelText,
            clName: group.state,
            btn   : stateLabel,
            group : "classes"
        ]
    }
    
    // Create a map for class labels
    uiElements.classLabelByClassName = uiElements.btnMap
        .findAll { it.group == "classes" }
        .collectEntries { [(it.clName): it] }
        
        
    // - Image classes summary labels -
    def allClassesLabel = new Label( "SUMMARY: ${session.classifyingName} subclassifications from ${session.classifierMode ? session.userName : session.classifiers}:" )
    allClassesLabel.setStyle( "-fx-font-weight: bold;" )

    uiElements.classesSummaryToggleGroup  = new ToggleGroup()
    uiElements.classesSummaryBtns         = []
    uiElements.classesSummaryStyleMap     = [:]
    uiElements.classLabelSummaryByClassName = [:]
    
    session.highlightStatesAndColorsMap.each { group ->
        makeClassesSummaryBtn( group, uiElements, session )
    }
    
    uiElements.classesSummaryGrid.getChildren().setAll( uiElements.classesSummaryBtns )
    
    uiElements.classesSummarySection.getChildren().addAll( allClassesLabel, uiElements.classesSummaryGrid )
    
    uiElements.btnMap << [ name  : "All user classifications label",
                           btn   : allClassesLabel,
                           group : "classesSummary" ]
    
    
    
    
    // - Reviewer classification buttons -
    def revClassLabel = new Label( "Set subclassification:" )
    revClassLabel.setStyle( "-fx-font-weight: bold;" )
    uiElements.revClassSection.getChildren().addAll( revClassLabel, uiElements.revClassGrid )
    
    uiElements.btnMap << [ name  : "Reviewer class label",
                           btn   : revClassLabel,
                           group : "revClass" ]
    
    session.reviewerStates.eachWithIndex { state, idx ->
        String numberTrigger = (idx + 6)
        String btnText = "(${numberTrigger}) ${state}"
        def revClassBtn = new Button( btnText )
        int classColorInt = session.statesAndColors.find { it.state == state }.color
        def c = new AwtColor( classColorInt, true )
        def btnStyle = "-fx-effect: dropshadow(gaussian, rgba(${c.red},${c.green},${c.blue},1), 3, 0.4, 0, 0)"
        uiElements.revClassGrid.getChildren().add( revClassBtn )
        
        String btnTT = "Set subclassification to \"${state}\""
        
        def action = {
            session.reviewerClassification = state
            queue.put( ActionStrings.SET_CLASS )
        }
        
        uiElements.btnMap << [
            name    : btnText,
            btn     : revClassBtn,
            btnTT   : btnTT,
            btnStyle: btnStyle,
            key     : numberTrigger,
            action  : action,
            group   : "revClass"
        ]
    }
    
    
    // - Channel buttons -
    def channelsLabel = new Label( "Channel controls:" )
    channelsLabel.setStyle( "-fx-font-weight: bold;" )
    uiElements.channelSection.getChildren().addAll( channelsLabel, uiElements.channelGrid )
    
    uiElements.btnMap << [ name   : "Channel label",
                           btn    : channelsLabel,
                           group  : "channel" ]
    
    session.channelSpecifics.eachWithIndex { ch, idx ->
        String numberTrigger = (idx + 1)
    
        String btnText = "(${idx+1}) ${ch.name}"
        ToggleButton btn = new ToggleButton( btnText )
        btn.setMnemonicParsing( false )
        // Small faint state caption shown under each channel button (off / on / scroll)
        def stateCaption = new Label()
        stateCaption.setStyle( "-fx-font-size: 10px;" )
        stateCaption.setOpacity( 0.6 )
        def btnWithCaption = new VBox( 1, btn, stateCaption )
        btnWithCaption.setAlignment( Pos.CENTER )
        uiElements.channelGrid.getChildren().add( btnWithCaption )
    
        def action = {
            session.toggleChannel = ch.name
            queue.put( ActionStrings.TOGGLE_CHANNEL )
        }
    
        uiElements.btnMap << [
            name   : btnText,
            chName : ch.name,
            btn    : btn,
            caption: stateCaption,
            key    : numberTrigger,
            action : action,
            group  : "channel"
        ]
    }
    
    // Create a map for channel buttons
    uiElements.channelEntryByChanName = uiElements.btnMap
        .findAll { it.group == "channel" }
        .collectEntries { [(it.chName): it] }
    
    
    // - Set bottom button bar buttons -
    List btnTypes = uiElements.btnMap.findAll { it.containsKey( "btnType" ) }.btnType
    alert.getButtonTypes().setAll( btnTypes )
    
    // Modify buttons according to their specifics
    uiElements.btnMap.each {
        if (it.containsKey( "btnType" ))  { it.btn = alert.getDialogPane().lookupButton( it.btnType ) }
        if (it.containsKey( "btnTT" ))    { setTT( it.btn, it.btnTT ) }
        if (it.containsKey( "btnStyle" )) { it.btn.setStyle( it.btnStyle ) }
    }
    
    // Set button actions
    // Not every entry has one - the class summary toggles do their work in a selectedProperty
    // listener instead, so they must fall through here rather than blow up on entry.action()
    uiElements.btnMap.each { entry ->
        if (!entry.btn || !entry.containsKey( "action" )) { return }
        entry.btn.addEventFilter( ActionEvent.ACTION ) { event ->
            if (entry.active) {  
                entry.action()
                event.consume()
            }
        }
    }
    
    
    // -- Keyboard shortcuts going to control panel --
    def scene = alert.getDialogPane().getScene()

    def shiftDown        = new AtomicBoolean( false )
    def shiftLongPressed = new AtomicBoolean( false )
    def shiftHoldTimer   = new PauseTransition( Duration.millis( 500 ) )
    
    // When key is pressed, start shift timer or run action
    scene.setOnKeyPressed { event ->
        def key = event.getText() ? event.getText().toLowerCase() : event.getCode()

        if (key == KeyCode.SHIFT) {
            if (shiftDown.getAndSet( true )) { return }
            shiftLongPressed.set( false )
            shiftHoldTimer.playFromStart()
            return
        }
        
        // Run key action if being used
        uiElements.btnMap.find { it.active && key in it.key }?.action()
    }
    
    // If shift has been held down long enough, enter free view
    shiftHoldTimer.setOnFinished {
        if (shiftDown.get() && !shiftLongPressed.get()) {
            shiftLongPressed.set( true )
            queue.put( ActionStrings.TOGGLE_FREE_VIEW )
        }
    }
    
    // Reset long shift timer
    scene.setOnKeyReleased { event ->
        if (event.getCode() == KeyCode.SHIFT) {
            shiftHoldTimer.stop()
            if (shiftDown.get() && !shiftLongPressed.get() && session.freeView) {
                queue.put( ActionStrings.SWITCH_FREE_VIEW_MODE )
            }
            shiftDown.set( false )
        }
    }

    // -- Mouse, scroll, and keyboard actions to viewer --
    def viewScene          = session.view.getScene()
    def originalDispatcher = viewScene.getEventDispatcher()

    // Variables to check for panning in free view mode
    boolean isPanning = false
    double panStartX = 0
    double panStartY = 0
    final double DRAG_THRESHOLD = 5.0
    
    // Read the clicks/scrolls on the viewer and pass them properly
    viewScene.setEventDispatcher( new EventDispatcher() {
        Event dispatchEvent( Event event, EventDispatchChain tail ) {
            def eventType = event.getEventType()
            
            if (alert.isShowing() && (event instanceof MouseEvent || 
                                      event instanceof ScrollEvent || 
                                      event instanceof KeyEvent)) {
                
                // Mouse event checks (click, drag, release)
                if (event instanceof MouseEvent) {
                    viewerSettings.hoverCheck = true // Checking mouse location for class labels
                    
                    if (eventType == MouseEvent.MOUSE_PRESSED) {
                        panStartX = event.x
                        panStartY = event.y
                        isPanning = false
                    }
                    
                    // If dragging in free view, let through to let user pan
                    if (eventType == MouseEvent.MOUSE_DRAGGED) {
                        if (session.freeView) {
                            double dx = event.x - panStartX
                            double dy = event.y - panStartY
                            if (Math.hypot( dx, dy ) > DRAG_THRESHOLD) {
                                isPanning = true
                                session.viewer.setCenterPixelLocation(
                                    session.viewer.getCenterPixelX() - dx * session.viewer.getDownsampleFactor(),
                                    session.viewer.getCenterPixelY() - dy * session.viewer.getDownsampleFactor()
                                )
                                panStartX = event.x
                                panStartY = event.y
                            }
                        }
                    }
                    
                    // When mouse is released, if not dragged, fire click action
                    if (eventType == MouseEvent.MOUSE_RELEASED) {
                        if (!isPanning) {
                            uiElements.btnMap.find { it.active && it.click == event.getButton() }?.action()
                        }
                        isPanning = false
                        
                        def window = alert.getDialogPane().getScene().getWindow()
                        Platform.runLater { window.requestFocus() } // So control panel keeps focus
                    }
                }
                
                // If scrolling, change brightness or let through to zoom
                if (event instanceof ScrollEvent) {           
                    
                    // Pass scrolls on to zoom in free view zoom mode. Block modifier keys
                    if (session.freeView && session.freeViewMode == ActionStrings.ZOOM) {
                        if (event.isControlDown() || event.isMetaDown() || event.isAltDown() || event.isShiftDown()) {
                            event.consume()
                            return event
                        }
                        return originalDispatcher.dispatchEvent( event, tail )
                    
                    // If not allowing for zoom, change brightness if scrolled enough
                    } else if (Math.abs( event.getDeltaY() ) >= 1) {
                        queue.put( event.getDeltaY() > 0 ? ActionStrings.BRIGHT_UP : ActionStrings.BRIGHT_DOWN )
                    }
                }
                
                // Block any other events (like keys)
                event.consume()
                return event
            }
            return originalDispatcher.dispatchEvent( event, tail )
        }
    } )

    // Restore normal dispatcher on close
    alert.setOnHidden {
        viewScene.setEventDispatcher( originalDispatcher )
    }

    // Header box (built once, content replaced by updateControlPanel)
    uiElements.headerBox = new VBox()
    uiElements.headerBox.setPadding( new Insets( 10 ) )
    uiElements.headerBox.setStyle("""
        -fx-background-color: -fx-box-border, -fx-control-inner-background;
        -fx-background-insets: 0, 1;
    """)
    alert.getDialogPane().setHeader( uiElements.headerBox )
}

// Function to update UI
def updateControlPanel( Alert alert, ViewerSessionVars session, elements uiElements, settings viewerSettings, queue ) { 
    
    // Set which buttons should now be available
    Set availableBtns = [ActionStrings.INSTRUCTIONS, ActionStrings.CANCEL, ActionStrings.TOGGLE_ANNOS] as LinkedHashSet
    if (session.freeView) {
        availableBtns.addAll( ActionStrings.TOGGLE_FOCUS, ActionStrings.TOGGLE_GRID )
        availableBtns.addAll( uiElements.btnMap.findAll { it.group == "classesSummary" }.name )
    } else if (session.classifierMode) {
        availableBtns.addAll( ActionStrings.REMOVE, ActionStrings.BACK, ActionStrings.NEXT, ActionStrings.TOGGLE_CLASS )
        // One at a time, the view is already on the annotation, so there is nothing to focus on
        if (!session.singleObjects) { availableBtns.add( ActionStrings.TOGGLE_FOCUS ) }
        availableBtns.addAll( uiElements.btnMap.findAll { it.group == "classes" }.name )
    } else if (session.reviewerMode) {
        availableBtns.addAll( ActionStrings.CANCEL, ActionStrings.TOGGLE_ANNOS, ActionStrings.BACK )
        availableBtns.addAll ( uiElements.btnMap.findAll { it.group == "revClass" }.name )
    }
    
    availableBtns.addAll ( uiElements.btnMap.findAll { it.group == "channel" }.name )
    
    // Map which buttons are active and which need to change
    uiElements.btnMap.each { it.active = (it.name in availableBtns) }
    
    // Show/hide appropriate buttons
    uiElements.btnMap.each {
        it.btn?.setVisible( it.active )
        it.btn?.setManaged( it.active )
    }
    
    // Collapse sections entirely when nothing in the group is active (avoids leftover gap)
    boolean classesActive = uiElements.btnMap.any { it.group == "classes" && it.active }
    uiElements.classesSection.setVisible( classesActive )
    uiElements.classesSection.setManaged( classesActive )
    
    boolean classesSummaryActive = uiElements.btnMap.any { it.group == "classesSummary" && it.active }
    uiElements.classesSummarySection.setVisible( classesSummaryActive )
    uiElements.classesSummarySection.setManaged( classesSummaryActive )
    
    boolean channelsActive = uiElements.btnMap.any { it.group == "channel" && it.active }
    uiElements.channelSection.setVisible( channelsActive )
    uiElements.channelSection.setManaged( channelsActive )
    
    boolean revClassesActive = uiElements.btnMap.any { it.group == "revClass" && it.active }
    uiElements.revClassSection.setVisible( revClassesActive )
    uiElements.revClassSection.setManaged( revClassesActive )
    
    // Whether the calls on show are saved: "Saved" once every annotation being classified has one, else "Unsaved"
    List nowClassifying = session.currentGroup ?: []
    if (session.classifierMode && nowClassifying) {
        int saved = nowClassifying.count { containsUserKey( it, session ) }
        String status = saved == nowClassifying.size() ? "Saved" : "Unsaved"
        uiElements.savedStatusLabel.setText( status )
        // Unsaved stands out; saved is quiet
        uiElements.savedStatusLabel.setStyle( saved < nowClassifying.size() ? "-fx-font-weight: bold; -fx-text-fill: #d08a00;" : "-fx-opacity: 0.75;" )
    }
    
    // Set labels for classifications in classifier mode
    session.statesAndColors.each { group ->
        def entry = uiElements.classLabelByClassName[ group.state ]
        def label = entry.btn
        
        String cssColor = intToFx( group.color ).toString().replace( "0x", "#" )
        
        
        entry.cssColor = cssColor
        
        def labelStyle = """
            -fx-border-color: ${cssColor};
            -fx-border-width: 1.5;
            -fx-border-radius: 3;
        """
        
        label.setStyle( labelStyle )
    }
    
    // Update class summary buttons if entering free view mode
    if (session.UI_COMMAND == ActionStrings.TOGGLE_FREE_VIEW && session.freeView) {
        // Key by state.toString() to match classLabelSummaryByClassName, which makeClassesSummaryBtn
        // fills using "String stateName = group.state" - keying by the raw List never matched, so
        // every button was torn down and rebuilt and the update pass below was skipped entirely
        def currentGroupsByState = session.highlightStatesAndColorsMap.collectEntries { [(it.state.toString()): it] }
        Set existingStates = uiElements.classLabelSummaryByClassName.keySet() as Set
        Set currentStates  = currentGroupsByState.keySet()
        
        // Remove stale buttons
        (existingStates - currentStates).each { state ->
            def entry = uiElements.classLabelSummaryByClassName.remove( state )
            uiElements.classesSummaryBtns.remove( entry.btn )
            uiElements.classesSummaryStyleMap.remove( entry.btn )
            entry.btn.setToggleGroup( null )
            uiElements.btnMap.removeAll { it.is( entry ) }
        }
        
        // Add new buttons
        (currentStates - existingStates).each { state ->
            makeClassesSummaryBtn( currentGroupsByState[ state ], uiElements, session )
        }
        
        // Update text, style, and lock dimensions for all current buttons
        currentGroupsByState.each { state, group ->
            def entry = uiElements.classLabelSummaryByClassName[ state ]
            if (!entry) return
            
            String cssColor = intToFx( group.color ).toString().replace( "0x", "#" )
            
            String labelText = "${state}  (${group.count})"
            entry.name     = labelText
            entry.cssColor = cssColor
            entry.btn.setText( labelText )
            
            def channelUnselectedStyle = "-fx-border-color: ${cssColor}; -fx-border-width: 1.5; -fx-border-radius: 3;"
            uiElements.classesSummaryStyleMap[ entry.btn ] = channelUnselectedStyle
            if (!entry.btn.isSelected()) entry.btn.setStyle( channelUnselectedStyle )
            lockDimensions( entry.btn )
        }
        
        uiElements.classesSummaryGrid.getChildren().clear()
        uiElements.classesSummaryGrid.getChildren().addAll( uiElements.classesSummaryBtns )
        uiElements.classesSummaryBtns.each { 
            it.setFocusTraversable( false )
            lockDimensions( it )
        }
    }
    
    
    // Creating channel button styles
    def channelStandardStyle = """
        -fx-padding: 6 10 6 10;
        -fx-border-radius: 6;
        -fx-background-radius: 6;
        -fx-border-color: #666666;
        -fx-border-width: 1.5;
    """.stripIndent()
    
    // Channel to change brightness style: full colored ring (same 3px colored border as the on-channel underline, all four sides) plus a faint colored glow so the actively-controllable channel still pops.
    // Padding is trimmed 1.5 per side to offset the thicker (1.5 -> 3) border so the button size stays constant.
    def highlightedStyle = { chan ->
        def c = new AwtColor( chan.getColor(), true )
        return "-fx-padding: 4.5 8.5 4.5 8.5; -fx-border-color: rgb(${c.red},${c.green},${c.blue}); -fx-border-width: 3; -fx-effect: dropshadow(gaussian, rgba(${c.red},${c.green},${c.blue},0.65), 10, 0.2, 0, 0);"
    }
    // Channel on style: skinny colored underline bar (color localized to one edge — distinct from the brightness channel's all-around glow)
    // Bottom padding is trimmed by the 1.5 the bottom border grows (1.5 -> 3) so the button height stays constant and the panel doesn't jump
    def channelSelectedStyle = { chan ->
        def c = new AwtColor( chan.getColor(), true )
        return "-fx-padding: 6 10 4.5 10; -fx-border-color: #666666 #666666 rgb(${c.red},${c.green},${c.blue}) #666666; -fx-border-width: 1.5 1.5 3 1.5; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 10, 0.2, 0, 0);"
    }
    def channelUnselectedStyle = "-fx-effect: innershadow(gaussian, rgba(0,0,0,0.9), 100, 0, 0, 0);" // Channel off style
    
    // Set channel button updated style
    session.channelSpecifics.each { ch ->
        
        // Creating and adding them
        def entry = uiElements.channelEntryByChanName[ ch.name ]
        def btn = entry.btn
        
        btn.setSelected( ch.state )
        
        // Adding their style
        boolean highlighted = (ch.channel == session.channelToChange) && (!session.freeView || session.freeViewMode != ActionStrings.ZOOM) // Setting initial style
        def channelButtonStyle = highlighted ? highlightedStyle( ch.channel ) : btn.isSelected() ? channelSelectedStyle( ch.channel ) : channelUnselectedStyle
        btn.setStyle( channelStandardStyle + channelButtonStyle )

        entry.caption?.setText( highlighted ? "scroll" : btn.isSelected() ? "on" : "off" )
        
        def chBtnTooltipText = highlighted ? "Toggle ${ch.name} \n \n Scroll on viewer to change brightness" : "Toggle ${ch.name}"
        setTT( btn, chBtnTooltipText )
    }
    
    
    // - Update instructions -
    String brightnessChannel = session.channelToChange ? session.channelSpecifics.find { it.channel == session.channelToChange }.name : "none"
    String scrollInstructions = session.freeViewMode == ActionStrings.ZOOM ? "Zoom" : "Brightness [${brightnessChannel}]"
    boolean annosOn = getAnnosVisibilityStatus( viewerSettings )
    boolean gridOn = viewerSettings.overlayOptions.getShowGrid()
    List revClassKeys = uiElements.btnMap.findAll { it.group == "revClass" && it.key }.key
    List channelKeys = uiElements.btnMap.findAll { it.group == "channel" && it.key }.key
    
    
    // Single-item = header/full-width row, two+ items = [key, ...description]
    def I  = { String key, String desc = null -> desc ? [key, desc] : [key] }
    def IC = { String key, Color c, String desc -> [key, c, desc] }  // for color rows
    
    // Instruction lines
    List toggleAnnosI  = [I("(A)",                          "Toggle annotations [${annosOn}]")]
    List toggleGridI   = [I("(G)",                          "Toggle grid [${gridOn}]")]
    List removeAnnosI  = [I("(R)",                          session.singleObjects ? "Remove subclassification" : "Remove group subclassifications")]
    List nextGroupI    = [I("(N)",                          session.singleObjects ? "Save and move to next" : "Save group and move to next")]
    List fvScrollI     = [I("(scroll)",                     "${scrollInstructions}")]
    List changeBrightI = [I("(scroll)",                     "Brightness [${brightnessChannel}]")]
    List goBackI       = [I("(B)",                          "Back to previous ${session.reviewerMode || session.singleObjects ? 'annotation' : 'group'}")]
    List cycleClassI   = [I("(left click anno)",            "Cycle class/colors")]
    List changeFocusI  = [I("(right click anno)",           "Focus/unfocus on anno")]
    List moveViewerI   = [I("(drag)",                       "Pan viewer")]
    List switchFvModeI = [I("(SHIFT)",                      "Switch scroll function")]
    List enterFvI      = [I("(hold SHIFT)",                 "Enter free view")]
    List exitFvI       = [I("(hold SHIFT)",                 "Exit free view")]
    List setRevClassI  = [I("(${revClassKeys.join(', ')})", "Set subclassification")]
    List toggleChanI   = [I("(${channelKeys.join(', ')})",  "Toggle channel")]
    List openI         = [I("(I) Hide Instructions:",       " ")]
    List closedI       = [I("(I) Show instructions",        " ")]
    List emptyI        = [I("")]
    
    
    // Conditionally building instructions
    def instructionLines = !session.instructions ? closedI
        : session.freeView    ? openI + emptyI + toggleAnnosI + toggleGridI + moveViewerI + changeFocusI + switchFvModeI + fvScrollI +  toggleChanI + emptyI + exitFvI
        : session.reviewerMode? openI + emptyI + toggleAnnosI + goBackI + toggleChanI + changeBrightI + setRevClassI + emptyI + enterFvI
        :                       openI + emptyI + toggleAnnosI + nextGroupI + goBackI + removeAnnosI + toggleChanI + changeBrightI + (session.singleObjects ? [] : changeFocusI) + cycleClassI + emptyI + enterFvI
    
    
    uiElements.headerBox.getChildren().setAll( makeLines( instructionLines ) )
    
    def dialogPane = alert.getDialogPane()
    dialogPane.getScene()?.getWindow()?.sizeToScene()
    disableNonEditableFocusTraversal( dialogPane )
    
    viewerSettings.hoverCheck = true // Checking mouse location for class labels
}

// Function for SMOOTHLY changing viewer center/zoom
def changeViewer( double centerX, double centerY, double vWidth, double vHeight, boolean isUM, ViewerSessionVars session, Double targetMag = null, Closure onComplete = null ) {
    if (targetMag == null) {
        double width_uM  = isUM ? vWidth  : vWidth  * session.pixelSize
        double height_uM = isUM ? vHeight : vHeight * session.pixelSize
        double umPerPx = Math.max( width_uM / session.view.getWidth(), height_uM / session.view.getHeight() ) * 1.1
        targetMag = 0.25 / umPerPx
    }

    Platform.runLater {
        session.activeAnimation?.stop()
        double startX    = session.viewer.getCenterPixelX()
        double startY    = session.viewer.getCenterPixelY()
        double logStart  = Math.log( session.viewer.getMagnification() )
        double logEnd    = Math.log( targetMag )
        double L         = logEnd - logStart
        double dx = centerX - startX
        double dy = centerY - startY
        long durationNs = session.viewerMoveTimeMS * 1_000_000L
        long[] startTime = [-1L]
        session.activeAnimation = new AnimationTimer() {
            @Override
            void handle(long now) {
                if (startTime[0] < 0) { startTime[0] = now }
                double t = Math.min( (now - startTime[0]) / (double) durationNs, 1.0 )
                double e = t < 0.5 ? 4*t*t*t : 1 - Math.pow( -2*t + 2, 3 ) / 2
                double panE = Math.abs(L) > 1e-6 ? (1.0 - Math.exp(-L * e)) / (1.0 - Math.exp(-L)) : e
                session.viewer.setMagnification( Math.exp( logStart + L * e ) )
                session.viewer.setCenterPixelLocation( startX + dx * panE, startY + dy * panE )
                if (t >= 1.0) {
                    stop()
                    onComplete?.call()
                }
            }
        }
        session.activeAnimation.start()
    }
}

// Function to save the current viewer state to be restored
def saveViewerState( ViewerSessionVars session ) {
    return [
        x  : session.viewer.getCenterPixelX(),
        y  : session.viewer.getCenterPixelY(),
        mag: session.viewer.getMagnification()
    ]
}


// Function to restore the viewer state (with function above)
def restoreViewerState( viewerState, ViewerSessionVars session ) {
    if (!viewerState) return
    
    //Double running execution so viewer doesn't "miss"
    (0..1).each {
        Platform.runLater {
            changeViewer( viewerState.x, viewerState.y, 0, 0, true, session, viewerState.mag )
        }
    }
}

// Function for changing viewing grid
def changeGrid( settings viewerSettings, boolean turnOnGrid, double spacingX = null, double spacingY = null ) {
    Platform.runLater {
        viewerSettings.overlayOptions.setShowGrid( turnOnGrid )
        if (spacingX) { PathPrefs.gridSpacingX.set( spacingX ) }
        if (spacingY) { PathPrefs.gridSpacingY.set( spacingY ) }
    }
}

// Function for changing stored classification
def changeClassification( annoToChange, ViewerSessionVars session ) {
    def classifiedAs = session.annoStateMap[ annoToChange ]
    int oldIndex = session.statesAndColors.state.indexOf( classifiedAs )
    int newIndex = (oldIndex + 1) % session.statesAndColors.size()
    def newGroup = session.statesAndColors[ newIndex ]
    def newClass = newGroup.state
    def newColor = newGroup.color
    session.annoStateMap.put( annoToChange, newClass )
    
    def value = annoToChange.getMetadata().get( session.userKey )
    
    // To change the color with changing classification
    annoToChange.setColor( newColor )
    
    Platform.runLater { session.viewer.repaintEntireImage() }
}

// Function for switching to next image (or remaining on current)
def getNextImage( nextImage ) {
    def thisImageData = getCurrentImageData()
    def nextImageData = nextImage.readImageData()
    
    if (getProjectEntry() != nextImage) {
        runOnFxThreadAndWait { getCurrentViewer().setImageData( nextImageData ) }
        return nextImageData
    } else {
        return thisImageData
    }
}

// Function to toggle annotation visibility or read whether they're on
def toggleAnnos( settings viewerSettings ) {
    boolean annosOn = viewerSettings.overlayOptions.getShowAnnotations()
    Platform.runLater {
        viewerSettings.overlayOptions.setShowAnnotations( !annosOn )
        if (annosOn) { resetSelection() }
    }
}

// Function to get annotation visibility
def getAnnosVisibilityStatus( settings viewerSettings ) {
    boolean annosOn = viewerSettings.overlayOptions.getShowAnnotations()
    return annosOn
}

// Function to toggle a channel's visibility
def toggleChannel( channelForToggle, ViewerSessionVars session ) {
    if (!channelForToggle) return // Skipping rest if there is no channel to change
    def display = session.viewer.getImageDisplay()
    boolean toggle = channelForToggle in display.selectedChannels()
    
    Platform.runLater {
        display.setChannelSelected( channelForToggle, !toggle )
        session.viewer.repaintEntireImage()
    }
    // Setting its new status back in lookup map
    session.channelSpecifics.find { it.channel == channelForToggle }.state = !toggle
}

// Function to change a channel's brightness
def changeBrightness( String changeDirection, ViewerSessionVars session ) {
    if (!session.channelToChange) return // Skipping rest if there is no channel to change
    
    String channelName = session.channelSpecifics.find { it.channel == session.channelToChange }.name
    
    def display = session.viewer.getImageDisplay()
    boolean repaint = true
    // Current display range
    float minDisplay = session.channelToChange.getMinDisplay()
    float maxDisplay = session.channelToChange.getMaxDisplay()
    float newMinDisplay = minDisplay
    float newMaxDisplay = maxDisplay
    // Retrieving brightness info
    float maxAvailable = session.brightnessInfo[ session.channelToChange ].maxAvailable
    float autoMinDisplay = session.brightnessInfo[ session.channelToChange ].autoMinDisplay
    def expSteps = (session.brightnessInfo[ session.channelToChange ].expSteps as List<Float>)
    def stdSteps = (session.brightnessInfo[ session.channelToChange ].stdSteps as List<Float>)
    // Helpers: previous/next value in a sorted list
    def prevVal = { List<Float> arr, float x ->
        if (!arr) return null
        int idx = Collections.binarySearch( arr, x )
        int pos = idx >= 0 ? idx : ~idx  // insertion point
        pos > 0 ? arr[pos - 1] : null
    }
    def nextVal = { List<Float> arr, float x ->
        if (!arr) return null
        int idx = Collections.binarySearch( arr, x )
        int pos = idx >= 0 ? idx + 1 : ~idx
        pos < arr.size() ? arr[ pos ] : null
    }
    // Code for changing channel brightness
    // Only changing brightness if channel is on
    if (session.channelToChange in display.selectedChannels()) {
        if (changeDirection == "UP") {
            if (minDisplay > autoMinDisplay) {
                def cand = prevVal( stdSteps, minDisplay )
                newMinDisplay = (!cand || cand < autoMinDisplay) ? autoMinDisplay : cand
            } else {
                newMaxDisplay = prevVal( expSteps, maxDisplay )
                if (!newMaxDisplay) {
                    session.message = "Maximum brightness! (${channelName})"
                    conditionallyFireMessage( session )
                    repaint = false
                }
            }
        } else if (changeDirection == "DOWN" ) {
            if (minDisplay < autoMinDisplay) {
                def cand = nextVal( stdSteps, minDisplay )
                newMinDisplay = (!cand || cand > autoMinDisplay) ? autoMinDisplay : cand
            } else {
                newMaxDisplay = nextVal( expSteps, maxDisplay )
                if (!newMaxDisplay) {
                    newMaxDisplay = maxAvailable
                    newMinDisplay = nextVal( stdSteps, minDisplay )
                    if (!newMinDisplay) {
                        session.message = "Minimum brightness! (${channelName})"
                        conditionallyFireMessage( session )
                        newMinDisplay = maxAvailable
                        repaint = false
                    }
                }
            }
        }
    }
    // Only triggering repaint if hasn't already been done
    if (repaint) {
        Platform.runLater {
            display.setMinMaxDisplay( session.channelToChange, newMinDisplay, newMaxDisplay )
            session.viewer.repaintEntireImage()
        }
    }
}

// Function to check if a single annotation matches specific metadata classification(s)
def matchesMdClassification( annoToCheck, List sameValues, List anyValues, boolean diffValues, ViewerSessionVars session ) {
    
    def md = annoToCheck.getMetadata()
    
    if (!session.classifierKeys.every { md.containsKey( it ) }) return false
    
    def vals = session.classifierKeys.collect { md.get( it ) }
    
    if (sameValues && vals.every { it == vals[0] && it in sameValues }) return true
    if (anyValues  && vals.any   { it in anyValues })                   return true
    if (diffValues && vals.toSet().size() > 1)                          return true
    
    return false
}

// Function to input and collect a list of annotations matching specific metadata classification(s)
// Utilizes the above function
def findAnnosByMdClassification( List annosToCheck, List sameValues, List anyValues, boolean diffValues, ViewerSessionVars session ) {
    
    annosToCheck.findAll { anno ->
        matchesMdClassification( anno, sameValues, anyValues, diffValues, session )
    }
}

// Function to check if an anno has userKey
def containsUserKey( annoToCheck, ViewerSessionVars session ) {
    def md = annoToCheck.getMetadata()
    return md.containsKey( session.userKey )
}

// Function to adjust x to middle of available space including the alert
def adjustX( ViewerSessionVars session, double boundsX, double boundsY, double cX ) {
    def viewerBounds = session.view.localToScreen( session.view.getBoundsInLocal() )
    double leftBound = Math.max( session.alertRightEdge, viewerBounds.getMinX() )
    double targetScreenX = (leftBound + viewerBounds.getMaxX()) / 2.0
    double viewerCenterX = (viewerBounds.getMinX() + viewerBounds.getMaxX()) / 2.0
    double gridPx = Math.min( boundsX, boundsY ) / session.pixelSize
    double targetDownsample = Math.max( gridPx / session.view.getWidth(), gridPx / session.view.getHeight() )
    double adjustedX = cX - (targetScreenX - viewerCenterX) * targetDownsample
    
    return adjustedX
}

// Function to conditionally toggle the viewer focus to/away from an annotation
def toggleFocus( ViewerSessionVars session ) {
    if (!session.zoomed) {
            
        // Check if the mouse point lies inside the selected ROI and focus into it if so
        if (session.hoveredAnno) {
            
            def roi = session.hoveredAnno.getROI()
            
            session.nonZoomedViewerState = saveViewerState( session )
            
            double adjustedX = adjustX( session, roi.getBoundsWidth(), roi.getBoundsHeight(), roi.getCentroidX() )
            
            changeViewer( adjustedX, roi.getCentroidY(), roi.getBoundsWidth() * 2,
                          roi.getBoundsHeight() * 2, false, session )
            session.zoomed = true
            
            double[] lastZoom = [0.0]  // Declared here so zoomListener can reference it
            
            // Add a zoom listener so if zoomed after focus, it's no longer considered focues
            // Meaning right clicking again won't take back to previous viewer
            def zoomListener = new QuPathViewerListener() {
            
                @Override
                void visibleRegionChanged( QuPathViewer v, java.awt.Shape shape ) {
                    double currentZoom = v.getDownsampleFactor()
                    if (Math.abs( currentZoom - lastZoom[0] ) > 0.1) {
                        v.removeViewerListener( this )
                        session.zoomed = false
                    }
                }
            
                @Override
                void imageDataChanged( QuPathViewer v, ImageData before, ImageData after ) {}
                @Override
                void selectedObjectChanged( QuPathViewer v, PathObject pathObj ) {}
                @Override
                void viewerClosed( QuPathViewer v ) {
                    v.removeViewerListener( this )
                }
            }
            
            // Small pause to track only when zoom actually changes after transition
            PauseTransition pause = new PauseTransition( Duration.millis( session.viewerMoveTimeMS * 1.1 ) )
            pause.setOnFinished {
                lastZoom[0] = session.viewer.getDownsampleFactor()
                session.viewer.addViewerListener( zoomListener )
            }
            pause.play()
        }
    } else {
        restoreViewerState( session.nonZoomedViewerState, session )
        session.zoomed = false
    }
}

// Define scroll blocker specifics
viewerSettings.scrollFilterKey = "scrollFilterON"
viewerSettings.scrollBlocker = { ScrollEvent e ->
    e.consume()  // stops QuPath from zooming
} as EventHandler<ScrollEvent>

// Function to add a scroll blocker (stops qupath from zooming in viewer)
def addScrollBlocker( ViewerSessionVars session, settings viewerSettings ) {
    if (!session.view.properties[ viewerSettings.scrollFilterKey ]) {
        session.view.addEventFilter( ScrollEvent.ANY, viewerSettings.scrollBlocker )
        session.view.properties[ viewerSettings.scrollFilterKey ] = true
    }
}

// Function to remove a scroll blocker (stops qupath from zooming in viewer)
def removeScrollBlocker( ViewerSessionVars session, settings viewerSettings ) {
    session.view.removeEventFilter( ScrollEvent.ANY, viewerSettings.scrollBlocker )
    session.view.properties.remove( viewerSettings.scrollFilterKey )
}

// Define hover timer specifics (checks if mouse is over anno)
// Get position and highlight respective class label
def processPosition( double x, double y, ViewerSessionVars session, elements uiElements, settings viewerSettings ) {
    def canvasPoint = getCurrentViewer().getView().screenToLocal( x, y )
    def pt = getCurrentViewer().componentPointToImagePoint(canvasPoint.getX(), canvasPoint.getY(), null, false)
    session.hoveredAnno = session.hoverAnnos.find { it.getROI().contains( pt.getX(), pt.getY() ) }
    
    def hoveredClass = session.hoveredAnno ? session.annoStateMap[ session.hoveredAnno ] : null
    def hoveredSpec = hoveredClass ? uiElements.classLabelByClassName[ hoveredClass ] : null
    def labelToHighlight = hoveredSpec?.btn
    
    def borderColor = hoveredSpec?.cssColor ?: "gray"
    def highlightStyles = ["-fx-background-color: -fx-background;", "-fx-background-radius: 5;",
                           "-fx-effect: dropshadow(gaussian, ${borderColor}, 20, 0.6, 0, 0);"]
    def highlightStr = " " + highlightStyles.join( " " )
    def normStyle = ( uiElements.lastLabelHighlighted?.getStyle() ?: "" ).replace( uiElements.lastHighlightStr ?: "", "" )
    uiElements.lastLabelHighlighted?.setStyle( normStyle )
    labelToHighlight?.setStyle( (labelToHighlight.getStyle() ?: "") + highlightStr )
    uiElements.lastLabelHighlighted = labelToHighlight
    uiElements.lastHighlightStr = highlightStr
}

viewerSettings.hoverTimer = new AnimationTimer() {
    @Override
    void handle( long now ) {    
        if (!viewerSettings.hoverCheck) { return }
        viewerSettings.hoverCheck = false
        def loc = MouseInfo.getPointerInfo().getLocation()
        processPosition( loc.x, loc.y, session, uiElements, viewerSettings )
    }
}

// Function to add the hover timer to check if mouse if over an anno
def addHoverFilter( ViewerSessionVars session, settings viewerSettings ) {
    viewerSettings.hoverTimer.start()
}

// Function to remove the hover timer to check if mouse if over an anno
def removeHoverFilter( ViewerSessionVars session, settings viewerSettings ) {
    viewerSettings.hoverTimer.stop()
}

// Function to return QuPath to pre-script state
def restoreToPrescript( ViewerSessionVars session, settings viewerSettings ) {
    removeScrollBlocker( session, viewerSettings )
    removeHoverFilter( session, viewerSettings )
    // Only the fillers this tool added (tagged with their annotation's ID), never the image's own detections
    def addedDets = session.hierarchy.getDetectionObjects().findAll { it.getMetadata()?.get( "annoID" ) }
    session.hierarchy.removeObjects( addedDets, true )
    session.allClassAnnos.each { it.setColor( null ) }

    Platform.runLater {
        def old = viewerSettings.oldOverlay
        viewerSettings.overlayOptions.setShowAnnotations( old.showAnnotations )
        viewerSettings.overlayOptions.setFillAnnotations( old.fillAnnotations )
        viewerSettings.overlayOptions.setShowDetections( old.showDetections )
        viewerSettings.overlayOptions.setFillDetections( old.fillDetections )
        viewerSettings.overlayOptions.setOpacity( old.opacity )
        PathPrefs.annotationStrokeThicknessProperty().set( viewerSettings.oldAnnoThickness )
        changeGrid( viewerSettings, viewerSettings.wasGridOn, viewerSettings.oGridX, viewerSettings.oGridY )
        ViewerPlusDisplayOptions.getSharedInstance().setShowOverview( viewerSettings.wasOverviewOn )
        session.viewer.repaintEntireImage()
    }
    
    fireHierarchyUpdate() 
}

// Function to switch fre view modes
def switchFVmode( ViewerSessionVars session, settings viewerSettings ) {
    int freeViewModeIdx = session.freeViewModes.indexOf( session.freeViewMode )
    session.freeViewMode = session.freeViewModes[ (freeViewModeIdx + 1) % session.freeViewModes.size() ]     
    
    Platform.runLater {
        if (session.freeViewMode == ActionStrings.ZOOM && session.view.properties[ viewerSettings.scrollFilterKey ]) {
            removeScrollBlocker( session, viewerSettings )
        } else if (session.freeViewMode == ActionStrings.BRIGHTNESS && !session.view.properties[ viewerSettings.scrollFilterKey ]) {
            addScrollBlocker( session, viewerSettings )
        }
    }
}

// Function to generate classifier/reviewer key for metadata
def generateUserKey( ViewerSessionVars session, modeString, String user ) {
    String mdKey = "${session.toolKeyPrefix}_${modeString}_${user}_${session.classifyingName}"
    return mdKey
}

// Function to get user from key
def getUserFromKey( String key ) {
    return key.split( "_" )[2]
}

// Function to get classifying name from key
def getClassifyingNameFromKey( String key ) {
    return key.split( "_" )[3]
}

// Function to show a temporary message that fades out
def showFadeOutMessage( label ) {
    def existing = label.getProperties().get( "fadeOut" )
    if ( existing ) existing.stop()

    label.setOpacity( 0.7 )
    label.setVisible( true )
    label.setManaged( true )

    def fadeOut = new FadeTransition( Duration.millis( 800 ), label )
    fadeOut.setDelay( Duration.millis( 700 ) )
    fadeOut.setToValue( 0.0 )
    fadeOut.setOnFinished {
        label.setVisible( false )
        label.setManaged( false )
    }
    label.getProperties().put( "fadeOut", fadeOut )
    fadeOut.play()
}

// Function to get euclidean distance between RGB ints
double colorDistanceHSB( int c1, int c2 ) {
    float[] hsb1 = java.awt.Color.RGBtoHSB( (c1 >> 16) & 0xFF, (c1 >> 8) & 0xFF, c1 & 0xFF, null )
    float[] hsb2 = java.awt.Color.RGBtoHSB( (c2 >> 16) & 0xFF, (c2 >> 8) & 0xFF, c2 & 0xFF, null )

    // Hue is circular, so take the shortest arc
    float dh = Math.abs( hsb1[0] - hsb2[0] )
    if (dh > 0.5f) { dh = 1.0f - dh }

    float ds = Math.abs( hsb1[1] - hsb2[1] )
    float db = Math.abs( hsb1[2] - hsb2[2] )

    // Weight hue most heavily, then brightness, then saturation
    return Math.sqrt( (dh * 2.0) ** 2 + (ds * 1.0) ** 2 + (db * 1.5) ** 2 )
}

// Function to generate visible color far from other existing colors
def generateVisibleColor( List existingColors, List reservedColors, double minDistance = 0.3 ) {
    Random rng = new Random()
    int newColor
    int attempts = 0
    def allColors = (existingColors + reservedColors).unique()
    do {
        float hue        = rng.nextFloat()
        float saturation = 0.25f + rng.nextFloat() * 0.35f
        float lightness  = 0.75f + rng.nextFloat() * 0.20f
        newColor = AwtColor.HSBtoRGB( hue, saturation, lightness )
        attempts++
        if (attempts > 500) { break } // Stop from infinite loop
    } while (
        newColor in allColors ||
        allColors.any { colorDistanceHSB( newColor, it ) < minDistance }
    )
    return newColor
}

// Builds/updates highlightStatesAndColorsMap based on current annotation classifications
def buildHighlightStatesAndColorsMap( ViewerSessionVars session, List mdKeys ) {
    session.highlightStatesAndColorsMap.each { 
        it.count = 0
        it.annos = []
    }

    session.allClassAnnos.each { anno ->
        def md = anno.getMetadata()

        List classifierVals = mdKeys.collect { md.get( it ) }.unique().sort()
        classifierVals.replaceAll { it == null ? "unclassified" : it }

        boolean sameClass = classifierVals.size() == 1
        def classMatch = session.highlightStatesAndColorsMap.find { it.state == classifierVals }

        if (sameClass && classifierVals[0] in session.statesAndColors.state) {
            int color = session.statesAndColors.find { it.state == classifierVals[0] }.color
            if (!classMatch) {
                session.highlightStatesAndColorsMap << [state: classifierVals, color: color, count: 1, annos: [anno]]
            } else {
                classMatch.color = color
                classMatch.count++
                classMatch.annos << anno
            }
        } else if (classMatch) {
            classMatch.count++
            classMatch.annos << anno
        } else {
            int newColor = generateVisibleColor(
                session.highlightStatesAndColorsMap.color,
                session.statesAndColors.color + session.channelSpecifics.color.collect { it & 0x00FFFFFF }
            )
            session.highlightStatesAndColorsMap << [state: classifierVals, color: newColor, count: 1, annos: [anno]]
        }
    }

    session.highlightStatesAndColorsMap.removeAll { it.count == 0 }
}

// Colors annotations using the (already built) highlightStatesAndColorsMap, populating currentAnnoColorsMap
def colorAnnosFromMap( ViewerSessionVars session, List mdKeys ) {
    session.allClassAnnos.each { anno ->

        def md = anno.getMetadata()

        List classifierVals = mdKeys.collect { md.get( it ) }.unique().sort()
        classifierVals.replaceAll { it == null ? "unclassified" : it }

        def classMatch = session.highlightStatesAndColorsMap.find { it.state == classifierVals }
        anno.setColor( classMatch?.color ?: ColorTools.makeRGB( 0, 0, 0 ) )
    }
}

// Function to stop nodes from becoming highlighted when clicked
def disableNonEditableFocusTraversal( Node node ) {
    
    node.setFocusTraversable( false )
    
    if (node instanceof Parent) {
        node.getChildrenUnmodifiable().each {
            disableNonEditableFocusTraversal( it )
        }
    }
}

// Compute width once then lock
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

// Function to fire a dialog message
def conditionallyFireMessage( ViewerSessionVars session ) {   
    long now = System.currentTimeMillis()    
    boolean enoughTimePassed = (now - session.lastMessageTime) > 5000
    boolean differentMessage = session.lastMessage != session.message
    
    if (session.message && (differentMessage || enoughTimePassed)) {
        Platform.runLater { Dialogs.showWarningNotification( "", session.message ) }
        session.lastMessage = session.message
        session.lastMessageTime = now
    }
}

// Functions to switch between FX colors and Int colors (UI vs annotation objects require different color classes)
@Field def intToFx = { int rgb ->
    Color.rgb(
        (rgb >> 16) & 0xFF,
        (rgb >> 8)  & 0xFF,
        rgb & 0xFF
    )
}
@Field def fXtoRGBInt = { Color fX ->
    fX = fX ?: Color.GRAY
    ((int)(fX.red   * 255) << 16) |
    ((int)(fX.green * 255) << 8)  |
    (int)(fX.blue   * 255)
}

// Function to read in all rows of extenal project file into completeMatrix
def readJSONtoPreferencesMap( jsonFile ) {
    if (jsonFile.length() > 0L && jsonFile.getText() != "null") {
        def inputGson = new Gson()
        def readDataFile = inputGson.fromJson( jsonFile.getText(), Object )
        if (readDataFile instanceof Map) {
            return readDataFile
        }
    }
    
    return [:]
}

// Function to write preferences to .json file
def writePreferencesMaptoJSON( preferencesFile, preferencesMap ) {
    def outputGson = new GsonBuilder().setPrettyPrinting().create()
    preferencesFile.text = outputGson.toJson( preferencesMap )
}

// Function to create icon for UI
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
// (what is chosen, what that will do), shown under its text as "Current: ..." and kept up to date while the
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

// Function to get user preferences for run
def getCodePreferences( ViewerSessionVars session, boolean firstPrompt ) {
    def currentImageData = getCurrentImageData()
    def project = getProject()
    def projectImages = getProject().getImageList()
    
    // Multi-used styles
    def underlineRedStyle = """
        -fx-border-color: red;
        -fx-border-width: 0 0 1 0;
    """
    def underlineClearStyle = underlineRedStyle.replace( "red", "transparent" ) // same space, so flagging never shifts the layout
    def newCellStyle = """
        -fx-border-color: #5a8a5a; 
        -fx-border-width: 2;
    """
    def redFadeMessageStyle = """
        -fx-font-size: 12px;
        -fx-text-fill: #ff6b6b;
    """
    def greenFadeMessageStyle = """
        -fx-font-size: 12px;
        -fx-text-fill: #5cb85c;
    """
    def userSelectedStyle = """
        -fx-border-color: black;
        -fx-border-width: 1;
        -fx-font-weight: bold;
        -fx-background-color: derive(-fx-base, -30%);
    """ 
    def userUnelectedStyle = "-fx-opacity: 0.7;"
    def chanBoxStyle = "-fx-border-color: #000000"
    
    
    // Make/get prefs folder as needed
    def projectDirectory = project.getBaseDirectory()
    def scriptPrefsFolderName = "AnnoTools-Data"
    def scriptPrefsFileName = "Manually-Classify_Preferences" + ".json"
    def prefsFolder = new File( projectDirectory, scriptPrefsFolderName )
    if (!prefsFolder.exists()) {
        // Create a subfolder inside that folder
        prefsFolder.mkdirs()
    }
    session.prefsFile = new File( prefsFolder, scriptPrefsFileName )
    // Make file if does not exist
    if (!session.prefsFile.exists()) {
        session.prefsFile.createNewFile()
    }
    
    // Getting existing/previous preferences
    def prePrefsMap = readJSONtoPreferencesMap( session.prefsFile )


    // -- Open the UI with preference options --
    runOnFxThreadAndWait {
        // --- Create the dialog ---
        def preferencesDialog = new Dialog()
        preferencesDialog.setTitle( "Manual Subclassification" )
        
        def exportPrefsBtnType = new ButtonType( " Export", ButtonBar.ButtonData.LEFT )
        def importPrefsBtnType = new ButtonType( " Import", ButtonBar.ButtonData.LEFT )
        
        preferencesDialog.getDialogPane().getButtonTypes().setAll( ButtonType.OK, ButtonType.CANCEL, exportPrefsBtnType, importPrefsBtnType )
        def okBtn = preferencesDialog.getDialogPane().lookupButton( ButtonType.OK )
        def cancelBtn = preferencesDialog.getDialogPane().lookupButton( ButtonType.CANCEL )
        def exportPrefsBtn = preferencesDialog.getDialogPane().lookupButton( exportPrefsBtnType )
        def importPrefsBtn = preferencesDialog.getDialogPane().lookupButton( importPrefsBtnType )
        
        addIcon( exportPrefsBtn, FontAwesome.Glyph.UPLOAD, 12 )
        addIcon( importPrefsBtn, FontAwesome.Glyph.DOWNLOAD, 12 )
        exportPrefsBtn.setFocusTraversable( false )
        importPrefsBtn.setFocusTraversable( false )
        
        ButtonBar.setButtonUniformSize( okBtn, false )
        
        setTT( exportPrefsBtn, "Export existing preferences to .json file" )
        setTT( importPrefsBtn, "Import preferences from .json file" )
        
        def root = new BorderPane()
        
        // Brings the preview at the bottom of the window up to date. Filled in further down, once the popouts' settings exist
        def refreshSettingsSummary
        root.setPrefHeight( 350 ) // width follows the content, so the channel box can widen the window
        
        // Define shared column constraints
        def colGrow = new ColumnConstraints()
        colGrow.setHgrow( Priority.ALWAYS )
        def colFixed = new ColumnConstraints()
        colFixed.setHgrow( Priority.NEVER )
        
        
        // --- TOP: checkbox toggling dropdown mode ---
        def classifierModeBtn = new ToggleButton( "Classifier Mode" )
        def reviewerModeBtn   = new ToggleButton( "Reviewer Mode" )
        
        setTT( classifierModeBtn, "Subclassify annotations" )
        setTT( reviewerModeBtn, "Review annotations with specific subclassifications from other users" )
        
        // Stop mode buttons from being untoggled (must click other)
        [classifierModeBtn, reviewerModeBtn].each { btn ->
            btn.addEventFilter( MouseEvent.MOUSE_PRESSED ) {
                if (btn.isSelected()) {
                    it.consume()
                }
            }
        }
        
        def modeButtons = new ToggleGroup()
        classifierModeBtn.setToggleGroup( modeButtons )
        reviewerModeBtn.setToggleGroup( modeButtons )
        
        def updateModeButtonStyles = {
            classifierModeBtn.setStyle(
                classifierModeBtn.isSelected() ? userSelectedStyle : userUnelectedStyle
            )
            reviewerModeBtn.setStyle(
                reviewerModeBtn.isSelected() ? userSelectedStyle : userUnelectedStyle
            )
        }
        
        modeButtons.selectedToggleProperty().addListener { obs, oldVal, newVal ->
            updateModeButtonStyles()
        }
        
        updateModeButtonStyles()
        
        
        // The buttons for the two popouts. They sit at the bottom of the window, each beside a preview of
        // what it is set to (see BOTTOM)
        def changeClassColorOrderBtn = new Button( "States & colors" )
        setTT( changeClassColorOrderBtn, "Change the states an annotation can be given, their order and their colors (both modes)" )
        
        def specifyReviewAnnosBtn = new Button ( "Review criteria" )
        setTT( specifyReviewAnnosBtn, "Choose which annotations should be reviewed (reviewer mode only)" )
        
        // Make bounding box, add children, assign to UI and set spacing
        def topRow = new HBox( 10 )
        topRow.setPadding( new Insets( 8 ) )
        topRow.getChildren().addAll(
            classifierModeBtn,
            reviewerModeBtn
        )
        topRow.getChildren().each {
            it.setPadding( new Insets( 10 ) )
        }
        
        // Widening the dialog box so that nothing is cut off
        topRow.setMinWidth( Region.USE_PREF_SIZE )
        root.top = topRow
        
        
        // --- LEFT: dropdown (user box) + text fields ---
        def userLabel = new Label()
        addIcon( userLabel, FontAwesome.Glyph.USER, 14 )

        // Locking height as usual, but width to whichever mode label ("Classifier:" /
        // "Reviewer:") is wider, so toggling between modes never resizes or clips it
        Platform.runLater {
            double h = userLabel.getHeight()
            userLabel.setMinHeight( h )
            userLabel.setPrefHeight( h )
            userLabel.setMaxHeight( h )

            String realText = userLabel.getText()
            double maxW = ["Classifier:", "Reviewer:"].collect { txt ->
                userLabel.setText( txt )
                userLabel.applyCss()
                userLabel.prefWidth( -1 )
            }.max()
            userLabel.setText( realText )

            userLabel.setMinWidth( maxW )
            userLabel.setPrefWidth( maxW )
            userLabel.setMaxWidth( maxW )
        }
        
        def userFieldNotice = new Label()
        userFieldNotice.setPadding( new Insets( 0, 0, 0, 10 ) )
        userFieldNotice.setVisible( false )
        userFieldNotice.setManaged( false )
        
        def userLabelRow = new HBox( 2, userLabel, userFieldNotice )   
        userLabelRow.setPadding( new Insets( 10, 0, 0, 0 ) )
        lockDimensions( userLabelRow )
        
        def users = new ComboBox()
        users.setMaxWidth( Double.MAX_VALUE )
        HBox.setHgrow( users, Priority.ALWAYS )
        setTT( [userLabel, users], classifierModeBtn.isSelected() ? "Select a classifier" : "Select a reviewer" )
        lockDimensions( users )
        
        def userInputField = new TextField()
        userInputField.setPromptText( "Add with ENTER" )
        setTT( userInputField, (classifierModeBtn.isSelected() ? "Type a new classifier" : "Type a new reviewer") + " + press enter to add" )
        userInputField.setMaxWidth( Double.MAX_VALUE )
        HBox.setHgrow( userInputField, Priority.ALWAYS )
        
        def addUserBtn = new Button()
        setTT( addUserBtn, classifierModeBtn.isSelected() ? "Add a new classifier" : "Add a new reviewer" )
        addIcon( addUserBtn, FontAwesome.Glyph.PLUS, 12 )
        def removeUserBtn = new Button()
        setTT( removeUserBtn, "Remove a user" )
        addIcon( removeUserBtn, FontAwesome.Glyph.MINUS, 12 )
        def removeUserDropdown = new ComboBox()
        removeUserDropdown.setMaxWidth( Double.MAX_VALUE )
        HBox.setHgrow( removeUserDropdown, Priority.ALWAYS )
        
        def populateUsersBtn = new Button()
        setTT( populateUsersBtn, "Populate classifier/reviewer options from existing annotations in project." )
        addIcon( populateUsersBtn, FontAwesome.Glyph.SEARCH, 12 )
        lockDimensions( populateUsersBtn )
        
        def userRow = new HBox( 5, users, addUserBtn, removeUserBtn, populateUsersBtn )
        lockDimensions( userRow )

        // Say why on hover, while disabled
        explainWhenDisabled( users ) { "No ${classifierModeBtn.isSelected() ? 'classifiers' : 'reviewers'} yet: add one with +, or search the project for them" }
        explainWhenDisabled( addUserBtn ) { "Type the new name, then press Enter" }
        explainWhenDisabled( removeUserBtn ) { "Choose the user to remove from the list" }
        explainWhenDisabled( populateUsersBtn ) { "Already searched the project's annotations" }

        // The button is a ListCell wired to the popup's ListView, so its "empty" refresh (on layout, or when
        // the skin reprocesses an items change) resets the text to null, wiping the prompt the skin had just
        // painted. Owning the cell makes that refresh repaint the prompt instead of blanking it: without this
        // the prompt only survived where something called users.show() afterwards.
        users.setButtonCell( new ListCell<String>() {
            @Override
            protected void updateItem( String item, boolean empty ) {
                super.updateItem( item, empty )
                setText( item != null ? item : users.getPromptText() )
            }
        } )

        // Disabling with "None available" prompt whenever the current option list is empty
        def updateUsersAvailability = {
            boolean noUsers = users.getItems() == null || users.getItems().isEmpty()

            users.setPromptText( noUsers ? "None available" : null )
            users.setDisable( noUsers )

            // Emptying the list clears the selected index but leaves the value behind, which would otherwise
            // keep painting the old name over the prompt
            if (noUsers) { users.getSelectionModel().clearSelection() }
        }

        def usersItemsListener = { c -> updateUsersAvailability() } as InvalidationListener
        users.getItems().addListener( usersItemsListener )
        users.itemsProperty().addListener { obs, oldItems, newItems ->
            oldItems?.removeListener( usersItemsListener )   // Otherwise every mode switch stacks another copy
            newItems?.addListener( usersItemsListener )
            updateUsersAvailability()
        }
        updateUsersAvailability()

        // Coloring new users with green border
        def newUsers = []
        users.setCellFactory { _ ->
            new ListCell<String>() {
                @Override
                protected void updateItem( String item, boolean empty ) {
                    super.updateItem( item, empty )
                    if (empty || item == null) {
                        setText( null )
                        setStyle( "" )
                    } else if (item in newUsers) {
                        setText( item )
                        setStyle( newCellStyle )
                    } else {
                        setText( item )
                        setStyle( "" )
                    }
                }
            }
        }
        
        // Set remove user option cells style
        removeUserDropdown.setCellFactory {
            new ListCell() {
                
                {
                    styleProperty().bind(
                        Bindings.when( hoverProperty() )
                            .then( "-fx-background-color: rgba(255,255,255,0.20);" )
                            .otherwise( "-fx-background-color: rgba(255,0,0,0.08);" )
                    )
                }
                
                @Override
                protected void updateItem(Object item, boolean empty) {
                    super.updateItem( item, empty )
        
                    if (empty || item == null) {
                        setText( null )
                        setGraphic( null )
                        return
                    }
                    
                    def nameLabel = new Label( item.toString() )
                    nameLabel.setPadding( new Insets( 0, 0, 0, 0 ) )
                    nameLabel.setStyle( "-fx-text-fill: white;" )
                    
                    def removeUserSpacer = new Region()
                    HBox.setHgrow( removeUserSpacer, Priority.ALWAYS )
                    
                    def xLabel = new Label("✖  ")
                    xLabel.setStyle( "-fx-text-fill: white;" )
                    
                    def cellBox = new HBox( nameLabel, removeUserSpacer, xLabel )
                    cellBox.setAlignment( Pos.CENTER_LEFT )
                    
                    setGraphic( cellBox )
                    setText( null )
                }
            }
        }
        
        // Set mode boolean variable and tip for add/remove user based on classifier/reviewer mode
        modeButtons.selectedToggleProperty().addListener { obs, oldToggle, newToggle ->
            session.classifierMode = (newToggle == classifierModeBtn)
            
            userLabel.setText( session.classifierMode ? "Classifier:" : "Reviewer:" )
            
            setTT( addUserBtn, session.classifierMode ? "Add a new classifier" : "Add a new reviewer")
            setTT( userInputField, (session.classifierMode ? "Type a new classifier" : "Type a new reviewer") + " + press enter to add" )
            setTT( removeUserBtn, session.classifierMode ? "Remove a classifier" : "Remove a reviewer")
            setTT( [userLabel, users], session.classifierMode ? "Select a classifier" : "Select a reviewer" )
        }
        
        
        
        def classifierOptions = FXCollections.observableArrayList()
        def reviewerOptions   = FXCollections.observableArrayList()
        
        boolean addedUser = false
        
        // When enter is pressed, add the new user
        userInputField.setOnAction {
            String newUser = userInputField.getText().trim()?.capitalize()
            
            if (newUser) {
                if (session.classifierMode && newUser !in classifierOptions) {
                    classifierOptions << newUser
                } else if (session.reviewerMode && newUser !in reviewerOptions) {
                    reviewerOptions << newUser
                } else {
                    userInputField.clear()
                    userFieldNotice.setStyle( redFadeMessageStyle )
                    userFieldNotice.setText( "*User exists*" )  
                    showFadeOutMessage( userFieldNotice )
                    return
                }
                
                newUsers << newUser
                
                userFieldNotice.setStyle( greenFadeMessageStyle )
                userFieldNotice.setText( "*Added ${newUser}*" )              
                showFadeOutMessage( userFieldNotice )
                
                userRow.getChildren().set( 0, users )
                
                userInputField.clear()
                addUserBtn.setDisable( false )
                users.setValue( newUser )
                addedUser = true
            }
        }
        
        // Enforcing criteria and firing messages
        userInputField.textProperty().addListener { obs, oldVal, newVal ->
            // Letters/numbers only
            if (!newVal.matches( "[A-Za-z0-9]*" )) {
                userInputField.setText( oldVal )
                
                userFieldNotice.setStyle( redFadeMessageStyle )
                userFieldNotice.setText( "*Letters/numbers*" )           
                showFadeOutMessage( userFieldNotice )
                return
            }
            
            // 15 characters max
            if (newVal.length() > 15) {
                userInputField.setText( oldVal )
                
                userFieldNotice.setText( "*15 chrs max*" )
                userFieldNotice.setStyle( redFadeMessageStyle )
                showFadeOutMessage( userFieldNotice )
                return
            }
        }
        
        // Set field to add a user when button clicked
        addUserBtn.setOnAction {
            addUserBtn.setDisable( true )
            userRow.getChildren().set( 0, userInputField )
            userInputField.requestFocus()
            addedUser = false
        }
        
        // If adding a user and focus is changed, cancelling add
        preferencesDialog.getDialogPane().addEventFilter( MouseEvent.MOUSE_PRESSED ) { e ->
            if (!userInputField.isHover()) {
                if (!addedUser) {
                    addUserBtn.setDisable( false )
                    userRow.getChildren().set( 0, users )
                }
            }
        }
        
        boolean removedUser = false
        List foundUsers = [] // what the project search found, as [user: name, mode: mode]

        // Listener for which user is clicked when removing
        def removeUserListener
        removeUserListener = { obs, oldVal, newVal ->
            if (oldVal != newVal) {
                if (session.classifierMode) {
                    classifierOptions.remove( newVal )
                } else {
                    reviewerOptions.remove( newVal )
                }

                newUsers.remove( newVal )

                // The list no longer holds everything the search found, so it can be run again
                if (foundUsers.any { it.user == newVal && (it.mode == ActionStrings.CLASSIFIER_MODE) == session.classifierMode }) {
                    populateUsersBtn.setDisable( false )
                }

                userFieldNotice.setStyle( redFadeMessageStyle )
                userFieldNotice.setText( "*Removed ${newVal}*" )           
                showFadeOutMessage( userFieldNotice )
                removedUser = true
            }
            
            removeUserDropdown.valueProperty().removeListener( removeUserListener )
            userRow.getChildren().set( 0, users )
            users.show()
            removeUserBtn.setDisable( false )
        }
        
        // Action when remove user button is clicked
        String prevSelectedUser
        removeUserBtn.setOnAction {
            removeUserBtn.setDisable( true )
            removedUser = false
            
            prevSelectedUser = users.getValue()
            userRow.getChildren().set( 0, removeUserDropdown )
            
            def currentOptions = session.classifierMode ? classifierOptions : reviewerOptions
            removeUserDropdown.setItems( FXCollections.observableArrayList( currentOptions ) )
            
            removeUserDropdown.valueProperty().addListener( removeUserListener )
        
            userRow.applyCss()
            userRow.layout()
            
            removeUserDropdown.show()
        }
        
        // If removing a user and focus is changed, cancelling add
        removeUserDropdown.showingProperty().addListener { obs, wasShowing, isShowing ->
            if (wasShowing && !isShowing && !removedUser) {
                removeUserDropdown.valueProperty().removeListener( removeUserListener )
                userRow.getChildren().set( 0, users )
                removeUserBtn.setDisable( false )
            }
        }
        
        // Running through project and populating user options and sending notice (parallel)
        populateUsersBtn.setOnAction {
            def scene = QuPathGUI.getInstance().getStage().getScene()
            scene.setCursor( Cursor.WAIT )
            populateUsersBtn.setDisable( true )
        
            Thread.start {
                def nThreads = Math.min( Runtime.getRuntime().availableProcessors(), projectImages.size() )
                def pool = Executors.newFixedThreadPool( nThreads )
                
                def futures = projectImages.collect { entry ->
                    pool.submit({
                        def imageData = null
                        try {
                            imageData = entry.readImageData()
                            return imageData.getHierarchy().getAnnotationObjects().collectMany { anno ->
                                anno.getMetadata().keySet().findAll { it.contains( session.toolKeyPrefix ) }
                            }
                        } catch (Exception ignored) {
                            return []
                        } finally {
                            try { imageData?.close() } catch (Exception ignored2) {}
                        }
                    } as Callable)
                }
                
                def uniqueUsers = futures.collectMany { it.get() }
                    .unique()
                    .collect { [user: it.split( "_" )[2], mode: it.split( "_" )[1]] }
                    .unique()
                    
                pool.shutdown()
                
                Platform.runLater {
                    foundUsers = uniqueUsers
                    def prevUser = users.getValue() // Capture before mutating items: unique() below clears the list, which wipes the ComboBox selection
                    def newNamesSet = uniqueUsers.toSet()
                    List newClassifiers = []
                    List newReviewers = []
                    
                    uniqueUsers.each {
                        if (it.mode == ActionStrings.CLASSIFIER_MODE) {
                            classifierOptions << it.user
                            newClassifiers << it.user
                        
                        } else {
                            reviewerOptions << it.user
                            newReviewers << it.user
                        }
                        
                        newUsers << it.user
                    }
                    
                    classifierOptions = classifierOptions.unique()
                    reviewerOptions = reviewerOptions.unique()
                    refreshSettingsSummary?.call() // the reviewer preview says whose classifications are looked at
                    
                    
                    // Restore the selection the in-place unique() above wiped: keep the previous user if still valid, else default to the first
                    if ( prevUser in users.getItems() ) {
                        users.setValue( prevUser )
                    } else if ( !users.getItems().isEmpty() ) {
                        users.setValue( users.getItems().first() )
                    }
                    
                    if ( !uniqueUsers.isEmpty() ) {
                        def msgParts = []
                        if (newClassifiers) { msgParts << "${newClassifiers.size()} classifiers" }
                        if (newReviewers) { msgParts << "${newReviewers.size()} reviewers" }
                        def msg = "*Added: ${msgParts.join(', ')}*"
                        userFieldNotice.setText( msg )
                        userFieldNotice.setStyle( greenFadeMessageStyle )
                        users.show()
                        
                    } else {
                        userFieldNotice.setText( "*None found*" )
                        userFieldNotice.setStyle( redFadeMessageStyle )
                    }
                    
                    showFadeOutMessage( userFieldNotice )
                    scene.setCursor( Cursor.DEFAULT )
                }
            }
        }
        
        
        
        // New text fields
        def annoClassLabel = new Label( " Annotation class:" )
        annoClassLabel.setPadding( new Insets( 10, 0, 0, 0 ) )
        addIcon( annoClassLabel, FontAwesome.Glyph.STAR_HALF_ALT, 14 )
        def annoClassField = new ComboBox()
        annoClassField.setMaxWidth( Double.MAX_VALUE )
        setTT( [annoClassLabel, annoClassField], "Class of annotations to be subclassified. \nPopulated from classes of existing objects in project." )
        lockDimensions( annoClassField )
        
        def projectPathClasses = project.getPathClasses().findAll { it?.getName() }    
        def summaryGson = new Gson()
        def imageClassCounts = [:]  // imageName -> [className -> count]
        def totalsByClass = [:]     // className -> total across all images
        
        def imageList = project.getImageList()
        def nThreads = Math.min( Runtime.getRuntime().availableProcessors(), imageList.size() )
        def summaryPool = Executors.newFixedThreadPool( nThreads )
        def resultMap = new ConcurrentHashMap()
        
        // Parallel finding anno summaries
        def summaryFutures = imageList.collect { entry ->
            summaryPool.submit({
                def summaryFile = entry.getEntryPath().resolve( "summary.json" )
                def counts = [:]
                if (Files.exists( summaryFile )) {
                    try {
                        def raw = new String( Files.readAllBytes( summaryFile ) )
                        def summary = summaryGson.fromJson( raw, Object )
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
        
        // Serial merge. no concurrent writes
        summaryFutures.each { f ->
            def (name, counts) = f.get()
            resultMap[ name ] = counts
        }
        summaryPool.shutdown()
        
        // Merge into imageClassCounts and totalsByClass
        resultMap.each { imgName, counts ->
            imageClassCounts[ imgName ] = counts
            counts.each { className, count ->
                totalsByClass.merge( className, count, Integer.&sum )
            }
        }
        
        // The project's name for the open image, which the summary counts above are keyed by (its file's
        // own name can differ, e.g. once renamed in the project, and would count its objects twice)
        def currentImageName = currentImageData ? project.getEntry( currentImageData )?.getImageName() : null

        // If an image is open, use its LIVE hierarchy for that image's class counts so unsaved
        // objects are reflected (both counts and class existence). Cheap since it is already loaded.
        if (currentImageData != null && currentImageName != null) {
            def liveCounts = [:]
            currentImageData.getHierarchy().getAnnotationObjects().each { obj ->
                def cn = classLabelForObject( obj )
                liveCounts[ cn ] = (liveCounts[ cn ] ?: 0) + 1
            }
            imageClassCounts[ currentImageName ] = liveCounts
            totalsByClass.clear()
            imageClassCounts.each { n, c -> c.each { className, count -> totalsByClass.merge( className, count, Integer.&sum ) } }
        }

        def currentImageCounts = currentImageName ? (imageClassCounts[ currentImageName ] ?: [:]) : [:]
        
        // Sort: only classes with existing annotations in project, by abundance
        List pathClassEntries = totalsByClass
            .collect { pc, count -> [name: pc, num: count, imageNum: currentImageCounts[pc] ?: 0] }
            .sort { a, b -> (b.num <=> a.num) ?: naturalCompare( a.name, b.name ) }
        
        // Set dropdown selection to show counts (in the open image / in the project). With no image open
        // there is nothing to count in it, so only the project's total is shown
        def classCountText = { item ->
            currentImageName ? "${item.name}  [${item.imageNum} / ${item.num}]" : "${item.name}  [${item.num}]"
        }
        annoClassField.setCellFactory {
            new ListCell() {      
                @Override
                protected void updateItem( item, boolean empty ) {
                    super.updateItem( item, empty )
                    setText( empty || item == null ? null : classCountText( item ) )
                }
            }
        }
        
        // Set selection to show same counts as dropdown
        annoClassField.setConverter( new StringConverter() {
            String toString( def item ) {
                return item == null ? "" : classCountText( item )
            }
            
            def fromString( String string ) {
                return null
            }
        })
        
        annoClassField.setItems( FXCollections.observableArrayList( pathClassEntries ) )

        if (pathClassEntries.isEmpty()) {
            annoClassField.setPromptText( "None available" )
            annoClassField.setDisable( true )
            explainWhenDisabled( annoClassField ) { "No annotations in this project yet" }
        }

        
        def classifyingNameOptions
        
        def classifyingNameLabel = new Label( " Subclassification name:" )
        addIcon( classifyingNameLabel, FontAwesome.Glyph.TAG, 14 )
        
        def classifyingNameNotice = new Label()
        
        classifyingNameNotice.setPadding( new Insets( 0, 0, 0, 10 ) )
        classifyingNameNotice.setVisible( false )
        classifyingNameNotice.setManaged( false )
        
        def classifyingNameLabelRow = new HBox( 2, classifyingNameLabel, classifyingNameNotice )
        classifyingNameLabelRow.setPadding( new Insets( 10, 0, 0, 0 ) )
        lockDimensions( classifyingNameLabelRow )
        
        def classifyingNameSelection = new ComboBox()
        classifyingNameSelection.setMaxWidth( Double.MAX_VALUE )
        HBox.setHgrow( classifyingNameSelection, Priority.ALWAYS )
        classifyingNameSelection.setEditable( true )
        classifyingNameSelection.setPromptText( "e.g. CAA" )
        setTT( [classifyingNameLabel, classifyingNameSelection], "Simple key for what is being subclassified to be stored in metadata (e.g. CAA). \nLast 5 are stored." )
        
        def populateClassifyingNamesBtn = new Button()
        setTT( populateClassifyingNamesBtn, "Populate subclassification name options from existing annotations in project." )
        addIcon( populateClassifyingNamesBtn, FontAwesome.Glyph.SEARCH, 12 )
        
        def classifyingNameRow = new HBox( 5, classifyingNameSelection, populateClassifyingNamesBtn )
        explainWhenDisabled( populateClassifyingNamesBtn ) { "Already searched the project's annotations" }
        lockDimensions( classifyingNameRow )
        
        // Enforcing criteria and firing messages
        classifyingNameSelection.getEditor().textProperty().addListener { obs, oldVal, newVal ->
            // Letters/numbers only
            if (!newVal.matches( "[A-Za-z0-9]*" )) {
                classifyingNameSelection.getEditor().setText( oldVal )
                
                classifyingNameNotice.setText( "*Letters/numbers*" )
                classifyingNameNotice.setStyle( redFadeMessageStyle )
                showFadeOutMessage( classifyingNameNotice )
                return
            }
            
            // 15 characters max
            if (newVal.length() > 15) {
                classifyingNameSelection.getEditor().setText( oldVal )
                
                classifyingNameNotice.setText( "*15 chrs max*" )
                classifyingNameNotice.setStyle( redFadeMessageStyle )
                showFadeOutMessage( classifyingNameNotice )
                return
            }
        }
        lockDimensions( classifyingNameSelection )
        
        // Running through project and populating classifying name options and sending notice (parallel)
        populateClassifyingNamesBtn.setOnAction {
            def scene = QuPathGUI.getInstance().getStage().getScene()
            scene.setCursor( Cursor.WAIT )
            populateClassifyingNamesBtn.setDisable( true )
        
            Thread.start {
                nThreads = Math.min( Runtime.getRuntime().availableProcessors(), projectImages.size() )
                def pool = Executors.newFixedThreadPool( nThreads )
                
                def futures = projectImages.collect { entry ->
                    pool.submit({
                        def imageData = null
                        try {
                            imageData = entry.readImageData()
                            return imageData.getHierarchy().getAnnotationObjects().collectMany { anno ->
                                anno.getMetadata().keySet().findAll { it.contains( session.toolKeyPrefix ) }
                            }
                        } catch (Exception ignored) {
                            return []
                        } finally {
                            try { imageData?.close() } catch (Exception ignored2) {}
                        }
                    } as Callable)
                }
                
                def uniqueNames = futures.collectMany { it.get() }
                    .unique()
                    .collect { getClassifyingNameFromKey( it ) }
                    .unique()
                
                pool.shutdown()
        
                Platform.runLater {
                    def newNamesSet = uniqueNames.toSet()
                    
                    classifyingNameOptions.addAll( uniqueNames )
                    classifyingNameOptions = classifyingNameOptions.unique()
                    classifyingNameSelection.setItems( classifyingNameOptions )
                    
                    // Coloring added names through search with green border
                    classifyingNameSelection.setCellFactory { _ ->
                        new ListCell<String>() {
                            @Override
                            protected void updateItem( String item, boolean empty ) {
                                super.updateItem( item, empty )
                                if (empty || item == null) {
                                    setText( null )
                                    setStyle( "" )
                                } else if (item in newNamesSet) {
                                    setText( item )
                                    setStyle( newCellStyle )
                                } else {
                                    setText( item )
                                    setStyle( "" )
                                }
                            }
                        }
                    }
                    
                    // Also style the button cell (what shows when dropdown is closed)
                    classifyingNameSelection.setButtonCell( classifyingNameSelection.getCellFactory().call( null ) )
                    
                    if ( !classifyingNameSelection.getItems().isEmpty() ) {
                        classifyingNameSelection.setValue( classifyingNameSelection.getItems().first() )
                    }
        
                    if ( !uniqueNames.isEmpty() ) {
                        classifyingNameNotice.setText( "*${uniqueNames.size()} added*" )
                        classifyingNameNotice.setStyle( greenFadeMessageStyle )
                        
                        classifyingNameSelection.show()
                    } else {
                        classifyingNameNotice.setText( "*None found*" )
                        classifyingNameNotice.setStyle( redFadeMessageStyle )
                    }
        
                    showFadeOutMessage( classifyingNameNotice )
                    scene.setCursor( Cursor.DEFAULT )
                }
            }
        }
        
        
        def gridSizeLabel = new Label( " Subclassification method:" )
        addIcon( gridSizeLabel, FontAwesome.Glyph.TH, 14 )
        
        // The method: a grid square of annotations at a time (with the size of a square beside it), or one
        // annotation at a time
        String byGridMethod = "Grid squares (µm):"
        String oneByOneMethod = "One at a time"
        def methodChoice = new ComboBox( FXCollections.observableArrayList( byGridMethod, oneByOneMethod ) )
        methodChoice.setValue( byGridMethod )
        methodChoice.setMinWidth( Region.USE_PREF_SIZE )
        setTT( methodChoice, "Subclassify a grid square of annotations at a time, or one annotation at a time." )
        
        def gridSizeField = new TextField()
        gridSizeField.setMaxWidth( Double.MAX_VALUE )
        gridSizeField.setPromptText( "Size of grouping grid (µm)" )
        setTT( gridSizeField, "Size of grouping grid (µm). Increasing this reduces the number of groups per image but increases objects per group." )
        
        def gridSizeNotice = new Label()
        gridSizeNotice.setStyle( redFadeMessageStyle )
        gridSizeNotice.setPadding( new Insets( 0, 0, 0, 10 ) )
        gridSizeNotice.setVisible( false )
        gridSizeNotice.setManaged( false )
        
        def gridSizeRow = new HBox( 2, gridSizeLabel, gridSizeNotice )
        gridSizeRow.setPadding( new Insets( 10, 0, 0, 0 ) )
        lockDimensions( gridSizeRow )
        
        // Enforcing criteria and firing messages
        gridSizeField.textProperty().addListener { obs, oldVal, newVal ->
            // Numbers only
            if (!newVal.matches( "\\d*" )) {
                gridSizeField.setText( oldVal )
                
                gridSizeNotice.setText( "*Numbers only*" )
                showFadeOutMessage( gridSizeNotice )
                return
            }
            
            // 5 numbers max
            if (newVal.length() > 5) {
                gridSizeField.setText( oldVal )
                
                gridSizeNotice.setText( "*5 nums max*" )
                showFadeOutMessage( gridSizeNotice )
                return
            }
        }
        
        // Can't leave the field without restoring value
        String oldGridSize = gridSizeField.getText()
        gridSizeField.focusedProperty().addListener { obs, oldVal, newVal ->
            if (newVal) {
                oldGridSize = gridSizeField.getText()
            } else if (gridSizeField.getText().isEmpty()) {
                gridSizeField.setText( oldGridSize )
            }
        }
        
        // The grid size sits beside the method it belongs to, and is greyed out for the other one
        HBox.setHgrow( gridSizeField, Priority.ALWAYS )
        def methodRow = new HBox( 5, methodChoice, gridSizeField )
        gridSizeField.disableProperty().bind( methodChoice.valueProperty().isEqualTo( oneByOneMethod ) )
        explainWhenDisabled( gridSizeField ) { "Not used when subclassifying one at a time" }
        
        // Create bounding box, add children and set onto UI
        def leftBox = new VBox( 10 )
        leftBox.setPadding( new Insets( 5 ) )
        leftBox.setPrefWidth( 265 )
        leftBox.getChildren().addAll(
            userLabelRow, userRow,
            annoClassLabel, annoClassField,
            classifyingNameLabelRow, classifyingNameRow,
            gridSizeRow, methodRow
        )
        
        root.left = leftBox
        
        
        
        // --- Checkbox for filled classified annos (top right), then BOTTOM: the settings previews ---
        def topRowSpacer = new Region()
        HBox.setHgrow( topRowSpacer, Priority.ALWAYS )
        
        // Closure: Make a simple switch, green when on unless it is given other colors
        def makeSwitch = { boolean initialValue = false, double height = 18, String onFill = "#4cd964", String offFill = "#c7c7cc" ->
            def switchOn = new SimpleBooleanProperty( initialValue )
            
            double width = height * 2
            def track = new Rectangle( width, height )
            track.arcWidth = height
            track.arcHeight = height
            track.fill = initialValue ? Color.web( onFill ) : Color.web( offFill )
            
            def switchRadius = height / 2.5
            def slider = new Circle( switchRadius )
            slider.fill = Color.WHITE
            
            def switchPane = new StackPane( track, slider )
            switchPane.setMinSize( width, height )
            switchPane.setMaxSize( width, height )
            switchPane.setPrefSize( width, height )
            switchPane.setPadding( new Insets(0) )
            
            def updateVisual = {
                boolean on = switchOn.get()
                track.fill = on ? Color.web( onFill ) : Color.web( offFill )
                slider.translateX = on ? (width/4) : -(width/4)
            }
            
            switchPane.setOnMouseClicked {
                switchOn.set( !switchOn.get() )
            }
            
            switchOn.addListener { obs, oldV, newV ->
                updateVisual()
            }
            
            updateVisual()
            
            return [node: switchPane, status: switchOn]
        }
        
        // A switch between working through new annotations and going back over finished ones. It always
        // starts on new: the choice is never remembered
        def checkingWorkSwitch = makeSwitch( false, 18, "#2f8fe6", "#2f8fe6" ) // one color: it's a choice of two, not on/off
        def newWorkLabel = new Label( "Subclassify new" )
        def checkWorkLabel = new Label( "Check existing" )
        newWorkLabel.setOnMouseClicked { checkingWorkSwitch.status.set( false ) }
        checkWorkLabel.setOnMouseClicked { checkingWorkSwitch.status.set( true ) }
        def showWorkChoice = {
            boolean checking = checkingWorkSwitch.status.get()
            newWorkLabel.setOpacity( checking ? 0.5 : 1 )   // the side not chosen is faded
            checkWorkLabel.setOpacity( checking ? 1 : 0.5 )
        }
        checkingWorkSwitch.status.addListener { obs, oldVal, newVal -> showWorkChoice() }
        showWorkChoice()
        def checkingWorkBox = new HBox( 6, newWorkLabel, checkingWorkSwitch.node, checkWorkLabel )
        checkingWorkBox.setAlignment( Pos.CENTER )
        setTT( [checkingWorkBox, newWorkLabel, checkWorkLabel, checkingWorkSwitch.node],
               "New: work through annotations that still need doing.\nCheck existing: go back over the ones you've finished." )
        
        // It sits at the right of the top row, across from the mode buttons
        topRow.setAlignment( Pos.CENTER_LEFT )
        topRow.getChildren().addAll( topRowSpacer, checkingWorkBox )
        
        // What the two popouts are set to, so they rarely need opening just to check: one line each, named
        // after the classifying name, with the button that changes it at the end. Filled in by
        // refreshSettingsSummary, once the popouts' settings exist. The review line shows in reviewer mode only
        def statesPrefix = new Label( "States:" )
        def classesSummary = new FlowPane( 6, 2 ) // wraps onto more lines when the states don't fit on one
        classesSummary.setAlignment( Pos.CENTER_LEFT )
        classesSummary.setPadding( new Insets( 0, 0, 0, 14 ) )
        classesSummary.setMinWidth( 0 )
        classesSummary.setPrefWrapLength( 0 ) // never widens the dialog: fitPreviews gives it the height it needs instead
        classesSummary.setPrefHeight( 18 )
        
        def reviewPrefix = new Label( "Reviewing:" )
        // The lead-ins are sentences naming the annotation class and subclassification name. They take the width
        // left of the button and wrap onto a second line when that isn't enough (fitPreviews sizes them)
        [statesPrefix, reviewPrefix].each {
            it.setMinWidth( 0 )
            it.setPrefWidth( 0 ) // never widens the dialog
            it.setMaxWidth( Double.MAX_VALUE )
            it.setWrapText( true )
            HBox.setHgrow( it, Priority.ALWAYS )
        }
        def reviewSummary = new Label( " " )
        reviewSummary.setMaxWidth( Double.MAX_VALUE )
        reviewSummary.setMinWidth( 0 )
        reviewSummary.setPrefWidth( 0 ) // never widens the dialog: a long line wraps, and fitPreviews gives it the height it needs
        reviewSummary.setWrapText( true )
        reviewSummary.setPrefHeight( 18 )
        reviewSummary.setPadding( new Insets( 0, 0, 0, 14 ) )
        
        // Whose subclassifications a review looks at, on a quieter line of its own under the criteria
        def reviewWhose = new Label()
        reviewWhose.setPadding( new Insets( 0, 0, 0, 14 ) )
        reviewWhose.setOpacity( 0.7 )
        reviewWhose.setMinWidth( 0 )
        reviewWhose.setPrefWidth( 0 )
        reviewWhose.setMaxWidth( Double.MAX_VALUE )
        reviewWhose.setWrapText( true )
        
        [changeClassColorOrderBtn, specifyReviewAnnosBtn].each {
            it.setMinWidth( Region.USE_PREF_SIZE )
            it.setPrefWidth( 125 ) // same width, so the two line up
        }
        // Each block: the sentence with its button at the end, then what it is set to on a line of its own
        // underneath, which has the whole width to itself
        def statesHead = new HBox( 8, statesPrefix, changeClassColorOrderBtn )
        statesHead.setAlignment( Pos.CENTER_LEFT )
        def statesRow = new VBox( 3, statesHead, classesSummary )
        def reviewHead = new HBox( 8, reviewPrefix, specifyReviewAnnosBtn )
        reviewHead.setAlignment( Pos.CENTER_LEFT )
        def reviewRow = new VBox( 3, reviewHead, reviewSummary, reviewWhose )
        
        def settingsBox = new VBox( 10, statesRow, reviewRow )
        settingsBox.setPadding( new Insets( 10, 5, 0, 5 ) )
        settingsBox.setStyle( "-fx-border-color: -fx-box-border transparent transparent transparent; -fx-border-width: 1 0 0 0;" )
        VBox.setMargin( settingsBox, new Insets( 12, 0, 0, 0 ) )
        
        root.bottom = new VBox( settingsBox )
        
        // Closure: Give each preview the height its text needs at the width it has (one line, or more when it
        // has to wrap), and make the window taller or shorter by the difference so nothing else is squeezed
        boolean fittingPreviews = false
        double previewsHeight = -1 // what the previews asked for at the last pass
        def fitPreviews = {
            if (fittingPreviews) { return }
            fittingPreviews = true
            Platform.runLater {
                fittingPreviews = false
                if (settingsBox.getWidth() <= 0) { return }
                def pane = preferencesDialog.getDialogPane()
                double before = previewsHeight >= 0 ? previewsHeight : settingsBox.prefHeight( settingsBox.getWidth() )
                settingsBox.applyCss()
                settingsBox.layout()
                [statesPrefix, reviewPrefix, classesSummary, reviewSummary, reviewWhose].each { preview ->
                    if (preview.getWidth() <= 0) { return }
                    preview.setPrefHeight( Region.USE_COMPUTED_SIZE )
                    double h = Math.ceil( preview.prefHeight( preview.getWidth() ) )
                    preview.setMinHeight( h )
                    preview.setPrefHeight( h )
                    preview.setMaxHeight( h )
                }
                // The window is resized by exactly how much the height the previews ask for has changed since the
                // last pass (a line wrapping, or the review line coming and going with the mode).
                // Measuring against the height on screen instead goes wrong when two changes land before the
                // window has caught up: the difference is then applied twice, and the spare height shows up as
                // a gap under the buttons
                def win = pane.getScene()?.getWindow()
                previewsHeight = settingsBox.prefHeight( settingsBox.getWidth() )
                double change = previewsHeight - before
                if (win?.isShowing() && Math.abs( change ) >= 0.5) { win.setHeight( win.getHeight() + change ) }
            }
        }
        [statesPrefix, reviewPrefix, classesSummary, reviewSummary, reviewWhose].each { it.widthProperty().addListener { obs, oldVal, newVal -> fitPreviews() } }
        
        // Changing applicable preferences live when switching between modes
        modeButtons.selectedToggleProperty().addListener { obs, oldToggle, newToggle ->
            boolean inClassifierMode = (newToggle == classifierModeBtn)
            
            gridSizeLabel.setVisible( inClassifierMode )
            methodRow.setVisible( inClassifierMode )
            reviewRow.setVisible( !inClassifierMode )
            reviewRow.setManaged( !inClassifierMode ) // takes no space in classifier mode: the window follows (fitPreviews)
            
            def userOptions = inClassifierMode ? classifierOptions : reviewerOptions
            users.setItems( userOptions )
            
            users.getSelectionModel().selectFirst()
            
            okBtn.setText( inClassifierMode ? "Begin subclassifying" : "Begin reviewing" )
            newWorkLabel.setText( inClassifierMode ? "Subclassify new" : "Review new" )
        }
        
        
        
        // --- CENTER: scrollable list of channels (max 3 allowed) ---
        // Create all channel checkboxes
        // Getting comomon available channels across project images
        def serverGson = new Gson()
        def serverPool = Executors.newFixedThreadPool( nThreads )
        
        def serverFutures = projectImages.collect { entry ->
            serverPool.submit({
                def serverFile = entry.getEntryPath().resolve( "server.json" )
                if (Files.exists( serverFile )) {
                    try {
                        def raw = new String( Files.readAllBytes( serverFile ) )
                        def server = serverGson.fromJson( raw, Object )
                        def channels = server?.get( "metadata" )?.get( "channels" )
                        return channels?.collect { baseChannelName( it.get( "name" ) as String ) } ?: null
                    } catch (Exception ignored) {}
                }
                return null
            } as Callable)
        }
        
        def allChannelLists = serverFutures.collect { it.get() }.findAll { it != null }
        serverPool.shutdown()
        
        def commonChannels = allChannelLists ? allChannelLists.inject { acc, channels -> acc.intersect( channels ) } : []
        
        def channelCheckboxes = commonChannels.collect { channelName ->
            def cb = new CheckBox( channelName )
            cb.setMnemonicParsing( false )
            return cb
        }
        
        // Create bounding box for channels and relevant options
        def fullChannelsBox = new VBox( 5 )
        fullChannelsBox.setPadding( new Insets( 0, 5, 5, 5 ) )
        fullChannelsBox.setStyle( chanBoxStyle )

        // Create bounding box for channel checkboxes, scrolling both ways when it can't all fit
        def channelCBsBox =  new VBox( 5 )
        channelCBsBox.setPadding( new Insets( 5 ) )
        def channelsScroll = new ScrollPane( channelCBsBox )
        VBox.setVgrow( channelsScroll, Priority.ALWAYS )

        // Add tooltip to the channel list
        def chBoxToolTipBaseText = "Select channels that will help subclassify objects."
        def chBoxToolTipText = chBoxToolTipBaseText
        setTT( channelsScroll, chBoxToolTipText )
        
        int maxChannels = 5
        
        // Items in top channel row
        def channelsLabel = new Label( "Select up to ${maxChannels} channels: [0]" )
        channelsLabel.setPadding( new Insets( 5, 0, 0, 0 ) )
        def fullChannelsBoxSpacer = new Region()
        fullChannelsBoxSpacer.setMinWidth( 10 )
        HBox.setHgrow( fullChannelsBoxSpacer, Priority.ALWAYS )
        def clearChannelsBtn = new Button()
        setTT( clearChannelsBtn, "Clear selected channels" )
        addIcon( clearChannelsBtn, FontAwesome.Glyph.TRASH, 15 )
        
        // Assemble top row and add as child of larger channel box
        def topChannelRow = new HBox( 5 )
        topChannelRow.setPadding( new Insets( 8 ) )
        topChannelRow.getChildren().addAll( channelsLabel, fullChannelsBoxSpacer, clearChannelsBtn )
        // Floor the label's width at its widest count, so the count changing (digits differ in width, e.g.
        // [1] vs [0]) never clips it or resizes the box. Measured once laid out, when the real font is
        // applied. Only a floor, never a fixed width, so a mis-measure can pad the label but never clip it
        boolean channelsLabelFloorSet = false
        channelsLabel.widthProperty().addListener { obs, oldW, newW ->
            if (!channelsLabelFloorSet && newW.doubleValue() > 0) {
                channelsLabelFloorSet = true
                Platform.runLater {
                    String liveText = channelsLabel.getText()
                    double widest = (0..maxChannels).collect { n ->
                        channelsLabel.setText( "Select up to ${maxChannels} channels: [${n}]" )
                        channelsLabel.applyCss()
                        channelsLabel.prefWidth( -1 )
                    }.max()
                    channelsLabel.setText( liveText )
                    channelsLabel.setMinWidth( widest )
                }
            }
        }
        // Keep the row height stable
        Platform.runLater {
            double rowHeight = topChannelRow.getHeight()
            topChannelRow.setMinHeight( rowHeight )
            topChannelRow.setPrefHeight( rowHeight )
            topChannelRow.setMaxHeight( rowHeight )
        }
        
        // Add channel checkboxes and listeners to enforce max number
        channelCheckboxes.each { cb ->
            cb.selectedProperty().addListener { obs, oldVal, newVal ->
                def selectedCount = channelCheckboxes.count { it.isSelected() }
                channelsLabel.setText( "Select up to ${maxChannels} channels: [${selectedCount}]" )
                
                chBoxToolTipText = chBoxToolTipBaseText
                if (selectedCount > 0) {
                    def selectedNames = channelCheckboxes.findAll { it.isSelected() }.collect { it.getText() }
                    chBoxToolTipText += " \n \n" + "Channels selected:"
                    selectedNames.each { chBoxToolTipText += "\n- " + it }
                }
                
                setTT( channelsScroll, chBoxToolTipText )

                if (selectedCount > maxChannels) {
                    // Too many – revert this one
                    cb.setSelected( false )
                }
            }
            channelCBsBox.getChildren().add( cb )
            cb.setFocusTraversable( false )
        }
        pinCheckboxesWhileScrolling( channelsScroll, channelCheckboxes )

        // Clear all checked channels when clear button is pressed
        clearChannelsBtn.setOnAction {
            channelCheckboxes.each { it.setSelected( false ) }
        }

        // Adding all the pieces together
        fullChannelsBox.getChildren().addAll( topChannelRow, channelsScroll )
        root.right = fullChannelsBox

        // The box's width, as fitBoxesToScreen expects it: wide enough for the widest channel plus the
        // scroll bar. The count label plays the select-all's part, as its width floor lands after showing
        def channelsBoxMap = [
            box         : fullChannelsBox,
            header      : topChannelRow,
            selectAll   : channelsLabel,
            CBs         : channelCheckboxes,
            minWidth    : 150,
            naturalWidth: {
                double widest = channelCheckboxes.collect { it.applyCss(); it.prefWidth( -1 ) }.max() ?: 0
                def vBar = channelsScroll.lookupAll( ".scroll-bar" ).find { it instanceof ScrollBar && it.orientation == Orientation.VERTICAL }
                double barW = (vBar != null && vBar.prefWidth( -1 ) > 0) ? vBar.prefWidth( -1 ) : 15 // always leave room for the bar
                double listW = widest + insetsW( channelCBsBox ) + insetsW( channelsScroll ) + barW
                return Math.ceil( Math.max( listW, topChannelRow.prefWidth( -1 ) ) + insetsW( fullChannelsBox ) )
            }
        ]
        fitBoxesToScreen( preferencesDialog ) { [channelsBoxMap] }
        
        preferencesDialog.getDialogPane().content = root
        
        // Formatting scrollable box
        topChannelRow.setStyle( "-fx-border-color: #000000; -fx-border-width: 0 0 1 0" )
        channelsScroll.setStyle( "-fx-background-color: transparent" )
        
        
        // Closures the help lines and the settings summary share
        def some    = { int n, String one, String many -> n == 1 ? "1 ${one}" : "${n} ${many}" }
        def listOut = { List xs -> xs.size() < 2 ? xs.join() : xs[ 0..-2 ].join( ", " ) + " and " + xs[ -1 ] } // "a, b and c"
        
        // Closure: Show a popout over this dialog and wait for its answer. A popout blocks every other
        // window, this dialog's help panel included, so that is put away while the popout is open
        def showPopout = { Dialog popout ->
            def mainWindow = preferencesDialog.getDialogPane().getScene().getWindow()
            popout.initOwner( mainWindow )
            def helpPanels = Window.getWindows().findAll { it instanceof Stage && it.isShowing() && it.getOwner().is( mainWindow ) }
            helpPanels.each { it.hide() }
            // Help was open, so the popout's own help opens with it
            if (helpPanels) {
                popout.setOnShown {
                    Platform.runLater {
                        def helpType = popout.getDialogPane().getButtonTypes().find { it.getButtonData() == ButtonData.HELP_2 }
                        if (helpType != null) { popout.getDialogPane().lookupButton( helpType ).fire() }
                    }
                }
            }
            def result = popout.showAndWait()
            helpPanels.each { it.show() }
            return result
        }
        
        // Closure: Refit a popout's window after rows are added or removed
        def refitPopout = { Dialog popout ->
            Platform.runLater {
                def win = popout.getDialogPane().getScene()?.getWindow()
                if (win instanceof Stage) { win.sizeToScene() }
            }
        }
        
        // --- Creating popout to change class order and assigned colors ---
        def states = ["true", "false", "maybe", "other", "unknown"]
        
        def defaultStateOrder = ["false", "true", "maybe"]
        
        def defaultReviewerStates = ["true", "false"]
        
        int minClassifierStates = 2
        int maxClassifierStates = 5
        
        int minReviewerStates = 2
        int maxReviewerStates = 3
        
        // Defaults
        def defaultStateOrderAndConfirmedColors = [
            "false": Color.RED,
            "true" : Color.WHITE,
            "maybe": Color.GRAY
        ]
        def automaticColorOptions = [Color.RED, Color.WHITE, Color.GRAY, Color.CYAN, Color.YELLOW, Color.web( "#FF69B4" )]
        
        // Stored output used elsewhere in the script
        def stateColorMap = []
        
        // Locked in values (before pressing ok, changing in the window are temporary)
        def lockedStateOrder = defaultStateOrderAndConfirmedColors.keySet()
        def lockedReviewerStates = defaultReviewerStates
        def lockedConfirmedColors = [:]
        lockedStateOrder.each { stateName ->
            lockedConfirmedColors[ stateName ] = defaultStateOrderAndConfirmedColors[ stateName ] ?: Color.GRAY
        }
        
        // Build/update stateColorMap to pass to the rest of the script
        def rebuildStateColorMap = {
            stateColorMap = []
            
            lockedStateOrder.eachWithIndex { stateName, idx ->
                def entry = [
                    state   : stateName as String,
                    color   : fXtoRGBInt( lockedConfirmedColors[ stateName ] as Color ),
                    revState: (stateName in lockedReviewerStates) as boolean
                ]
                stateColorMap << entry
            }
        }
        
        rebuildStateColorMap()
        
        
        // Action when the class/color selection is opened
        changeClassColorOrderBtn.setOnAction {
            def classColorDialog = new Dialog<ButtonType>()
            classColorDialog.setTitle( "Assign state order and colors" )
            
            classColorDialog.getDialogPane().getButtonTypes().setAll(
                ButtonType.OK,
                ButtonType.CANCEL
            )
            
            def okButton = classColorDialog.getDialogPane().lookupButton( ButtonType.OK )
            
            // Making items
            def classColorGrid = new GridPane()
            classColorGrid.setHgap( 10 )
            classColorGrid.setVgap( 10 )
            classColorGrid.setPadding( new Insets( 10 ) )
            
            def dragHeaderSpacer = new Region()
            dragHeaderSpacer.setPrefWidth( 5 )
            
            def comboHeaderSpacer = new Region()
            comboHeaderSpacer.setPrefWidth( 160 )
            
            def confirmedHeader = new Label( "Color:" )
            confirmedHeader.setPadding( new Insets( 5, 0, 0, 0 ) )
            confirmedHeader.setPrefWidth( 131 )
            
            def tempStateOrder = new ArrayList<>( lockedStateOrder )
            def tempRevStates = lockedReviewerStates
            def tempConfirmedColors = [:]
            lockedConfirmedColors.each { k, v ->
                tempConfirmedColors[ k ] = v
            }
            
            def stateBoxes = tempStateOrder.collect { new ComboBox<String>() }
            def colorPickers = tempStateOrder.collect { new ColorPicker() }
            def reviewerCheckboxes = tempStateOrder.collect { new CheckBox() }
            
            
            def reviewerClassHeader = new Label( "Reviewer classes (${minReviewerStates}-${maxReviewerStates}):" )
            reviewerClassHeader.setPadding( new Insets( 5, 0, 0, 0 ) )
            reviewerClassHeader.setPrefWidth( 140 )
            reviewerClassHeader.setStyle( underlineRedStyle )
            
            // Leaves room at the end of each row for its remove button
            def headerTailSpacer = new Region()
            headerTailSpacer.setPrefWidth( 40 )
            
            def draggableHeaderRow = new HBox( 6 )
            draggableHeaderRow.getChildren().addAll(
                dragHeaderSpacer,
                comboHeaderSpacer,
                confirmedHeader,
                reviewerClassHeader,
                headerTailSpacer
            )
             
            
            def colLabels = new ColumnConstraints()
            colLabels.setHgrow( Priority.NEVER )
            
            def colDragArea = new ColumnConstraints()
            colDragArea.setHgrow( Priority.ALWAYS )
            colDragArea.setFillWidth( true )
            
            def colConfirmedHeader = new ColumnConstraints()
            colConfirmedHeader.setHgrow( Priority.NEVER )
            
            classColorGrid.getColumnConstraints().addAll(
                colLabels,
                colDragArea,
                colConfirmedHeader
            )
            
            def restoreDefaultsBtn = new Button( " Restore defaults" )
            setTT( restoreDefaultsBtn, "Restore state order and colors to default." )
            addIcon( restoreDefaultsBtn, FontAwesome.Glyph.UNDO, 15 )
            
            def stateLabelBox = new VBox()
            stateLabelBox.setPadding( new Insets( 2, 0, 2, 0 ) )
            
            ListView<Integer> rowListView
            
            def rowItems = FXCollections.observableArrayList( (0..<tempStateOrder.size()).collect { it } )
            rowListView = new ListView<Integer>( rowItems )
            rowListView.setFixedCellSize( 34 )
            rowListView.setMaxWidth( Double.MAX_VALUE )
            rowListView.setFocusTraversable( false )
            
            classColorGrid.add( restoreDefaultsBtn, 0, 0 )
            classColorGrid.add( draggableHeaderRow, 1, 0 )
            classColorGrid.add( stateLabelBox, 0, 1 )
            classColorGrid.add( rowListView, 1, 1 )

            
            classColorDialog.getDialogPane().setContent( classColorGrid )
            
            // Stopping the row from staying highlighted on mouse release
            rowListView.setOnMouseReleased { rowListView.getSelectionModel().clearSelection() }
            
            
            def refreshStateLabelBox
            
            def updatingSelections = false
            
            // Closure: Format all state boxes and color selectors
            def formatControls = {
                updatingSelections = true
                try {
                    tempStateOrder.eachWithIndex { state, idx ->
                        
                        def sb = stateBoxes[ idx ]
                        sb.getItems().addAll( states )
                        sb.setEditable( true )
                        setTT( sb, "Select or type a state name" )
                        sb.setPromptText( "Type or select" )
                        sb.setPrefWidth( 140 )
                        
                        def cp = colorPickers[ idx ]
                        setTT( cp, "Select color for corresponding state" )
                        
                        def rCB = reviewerCheckboxes[ idx ]
                        rCB.setPadding( new Insets( 5, 0, 0, 0 ) )
                        setTT( rCB, "Should reviewer be able to set objects to this class?" )
                        
                    }
                } finally {
                    updatingSelections = false
                }
            }
            
            // Closure: Set values to state and color selectors from local variables
            def syncControlsFromMaps = {
                updatingSelections = true
                try {
                    tempStateOrder.eachWithIndex { state, idx ->
                        
                        def sb = stateBoxes[ idx ]
                        sb.setValue( state )
                        
                        def cp = colorPickers[ idx ]
                        cp.setValue( tempConfirmedColors[ state ] )
                        
                        def rCB = reviewerCheckboxes[ idx ]
                        rCB.setSelected( state in tempRevStates )
                    }
                } finally {
                    updatingSelections = false
                }
            }
            
            // Closure: Collect state and color values and store in local variables/maps
            def syncMapsFromControls = {
                tempStateOrder = stateBoxes.collect { it.getValue() }
                tempRevStates = []
                
                stateBoxes.eachWithIndex { cb, idx ->
                    String stateName = cb.getValue()
                    
                    if (stateName) {
                        tempConfirmedColors[ stateName ] = colorPickers[ idx ].getValue()
                        
                        if (reviewerCheckboxes[ idx ].isSelected()) {
                            tempRevStates << stateName
                        }
                    }
                }
            }
            
            // Closure: Each reason the "OK" button can be disabled, with whether it applies right now
            String wrongNumRevClassesReason = "Select ${minReviewerStates}-${maxReviewerStates} reviewer classes"
            def okButtonBlockers = {
                def rowN = stateBoxes.size()
                def stateBoxValues = stateBoxes.collect { it.getValue() }
                def colorPickerValues = colorPickers.collect { it.getValue() }
                int numRevClasses = reviewerCheckboxes.count { it.isSelected() }
                [
                    ("Use ${minClassifierStates}-${maxClassifierStates} states" as String): rowN < minClassifierStates || rowN > maxClassifierStates,
                    (wrongNumRevClassesReason)                    : numRevClasses < minReviewerStates || numRevClasses > maxReviewerStates,
                    "Give every state a name and a colour"        : (stateBoxValues + colorPickerValues).any { !it },
                    "Give every state a different name"           : stateBoxValues.findAll { it }.with { it.size() != it.unique( false ).size() },
                    "Give every state a different colour"         : colorPickerValues.findAll { it }.with { it.size() != it.unique( false ).size() }
                ]
            }

            // Closure: Disabling "OK" button if any criteria are violated
            def updateOkButton = {
                def blockers = okButtonBlockers()

                // Also highlighting label if wrong number
                reviewerClassHeader.setStyle( blockers[ wrongNumRevClassesReason ] ? underlineRedStyle : underlineClearStyle )

                okButton.setDisable( blockers.any { it.value } )
            }
            explainWhenDisabled( okButton, okButtonBlockers ) // says why on hover, while disabled
            
            // Closure: Attach a state box listener that blocks invalid inputs and updates respective lists/maps
            def attachStateListeners = { item ->
                
                item.valueProperty().addListener { obs, oldVal, newVal ->
                    
                    if (updatingSelections) { return }
                    updatingSelections = true
                    
                    try {
                        // If state box is empty, setting to last state
                        if (!newVal) {
                            item.setValue( oldVal )
                            return
                        }
                        
                        // If an existing state is entered, switching the two states
                        def restOfBoxes = stateBoxes.findAll { it != item }
                        restOfBoxes.each { box ->
                            if (box.getValue() == newVal) {
                                box.setValue( oldVal )
                            }
                        }  
                        
                        syncMapsFromControls()
                        rowListView.refresh()
                        refreshStateLabelBox()
                        updateOkButton()
                    
                    } finally {
                        updatingSelections = false
                    }
                }
                
                // Letters/numbers only and no more than 15 characters
                item.getEditor().textProperty().addListener { obs, oldVal, newVal ->
                    if (!newVal.matches( "[A-Za-z0-9]*" ) || newVal.length() > 15) {
                        item.getEditor().setText( oldVal )
                    }
                }
            }
            
            // Closure: Attach a color listener to stop duplicate colors and updates respective lists/maps
            def attachColorListener = { item ->
                 
                item.valueProperty().addListener { obs, oldVal, newVal ->
                    
                    if (updatingSelections) { return }   
                    updatingSelections = true
                    
                    try {
                        def restOfPickers = colorPickers.findAll { it != item }
                        def usedColors = restOfPickers.collect { it.getValue() }
                        
                        // Blocking new color if already being used
                        if (newVal in usedColors) {
                            item.setValue( oldVal )
                            return
                        }
                        
                        syncMapsFromControls()
                        rowListView.refresh()
                        refreshStateLabelBox()
                        updateOkButton()
                    
                    } finally {
                        updatingSelections = false
                    }
                }
            }
            
            def RevCBListener = { obs, oldVal, newVal ->  
                if (updatingSelections) { return }
                
                updatingSelections = true
                try {
                    updateOkButton()
                    syncMapsFromControls()
                } finally {
                    updatingSelections = false
                }
            }
            
            // Attaching listeners to existing objects
            stateBoxes.each { cb ->
                attachStateListeners( cb )
            }
            
            colorPickers.each { cp ->
                attachColorListener( cp )
            }
            
            reviewerCheckboxes.each { rCB ->
                rCB.selectedProperty().addListener( RevCBListener )
            }
            
            // Closure: Create a new state box, add options and listener
            def createStateBox = {
                def sb = new ComboBox<String>()
                attachStateListeners( sb )
                return sb
            }
            
            // Closure: Create a new color picker and add listener
            def createColorPicker = {
                def cp = new ColorPicker()
                attachColorListener( cp )
                return cp
            }
            
            // Closure: Create a new checkbox and add listener
            def createRevCB = {
                def rCB = new CheckBox()
                rCB.selectedProperty().addListener( RevCBListener )
                return rCB
            }
            
            def refreshRowItems = {
                rowItems.setAll( (0..<tempStateOrder.size()).collect { it } )
            }
            
            // Closure: Add class/color row from pane and associated maps/lists
            def addRow = { int insertIdx ->
                if (tempStateOrder.size() >= maxClassifierStates) return
                
                syncMapsFromControls()
                
                def unusedStates = states.findAll { !(it in tempStateOrder) }
                def firstUnusedState = unusedStates ? unusedStates[ 0 ] : null
                
                tempStateOrder.add( insertIdx, firstUnusedState )
                
                def usedColors = colorPickers.collect { it.getValue() }
                def firstUnusedColor = automaticColorOptions.find { it !in usedColors }
                
                stateBoxes.add( insertIdx, createStateBox() )
                colorPickers.add( insertIdx, createColorPicker() )
                reviewerCheckboxes.add( insertIdx, createRevCB() )
                
                // Getting rid of any convoluting keys to keep map correct size
                tempConfirmedColors.keySet().retainAll( tempStateOrder )
                tempConfirmedColors[ firstUnusedState ] = defaultStateOrderAndConfirmedColors[ firstUnusedState ] && defaultStateOrderAndConfirmedColors[ firstUnusedState ] !in usedColors ? defaultStateOrderAndConfirmedColors[ firstUnusedState ] : firstUnusedColor
                
                formatControls()
                syncControlsFromMaps()
                refreshRowItems()
                rowListView.refresh()
                refreshStateLabelBox()
                updateOkButton()
            }
            
            // Closure: Delete class/color row from pane and associated maps/lists
            def removeRow = { int idx ->
                if (tempStateOrder.size() <= minClassifierStates) return
                if (idx < 0 || idx >= tempStateOrder.size()) return
                
                syncMapsFromControls()
                
                tempStateOrder.remove( idx )
                stateBoxes.remove( idx )
                colorPickers.remove( idx )
                reviewerCheckboxes.remove( idx )
                
                syncControlsFromMaps()
                refreshRowItems()
                rowListView.refresh()
                refreshStateLabelBox()
                updateOkButton()
            }
            
            // Closure: Move a class/color row to a new location once dragged and dropped
            def moveRow = { int fromIdx, int toIdx ->
                if (fromIdx == toIdx || fromIdx < 0 || toIdx < 0) return
                if (fromIdx >= tempStateOrder.size() || toIdx > tempStateOrder.size()) return
                
                syncMapsFromControls()
                
                def movedState = tempStateOrder.remove( fromIdx )
                def movedPicker = colorPickers.remove( fromIdx )
                def movedBox = stateBoxes.remove( fromIdx )
                def movedReviewerCB = reviewerCheckboxes.remove( fromIdx )
                
                if (fromIdx < toIdx)
                    toIdx--
                
                tempStateOrder.add( toIdx, movedState )
                colorPickers.add( toIdx, movedPicker )
                stateBoxes.add( toIdx, movedBox )
                reviewerCheckboxes.add( toIdx, movedReviewerCB )
                
                syncControlsFromMaps()
                rowListView.refresh()
                updateOkButton()
            }
            
            
            // Closure: Clear any style changes during state cell dragging
            def clearMarker = { ListView<Integer> lv ->
                lv.lookupAll( ".list-cell" ).each { it.setStyle( "" ) }
            }
            
            // Closure: Create style of line to be shown when dragging states
            def edgeStyle = { boolean top, boolean bottom, int w = 3 ->
                def topColor = top ? "#3b82f6" : "transparent"
                def bottomColor = bottom ? "#3b82f6" : "transparent"
                def topWidth = top ? "${w}" : "0"
                def bottomWidth = bottom ? "${w}" : "0"
                
                return "-fx-border-color: ${topColor} transparent ${bottomColor} transparent; " +
                       "-fx-border-width: ${topWidth} 0 ${bottomWidth} 0;"
            }
            
            // Closure: Show a temporary line between cells showing where the reorder will be
            def showMarker = { ListCell<Integer> rowCell, boolean dropAbove, int fromIdx, ListView<Integer> lv ->
                clearMarker( lv )
                
                int idx = rowCell.getIndex()
                int maxIdx = tempStateOrder.size() - 1
                def visibleCells = lv.lookupAll(".list-cell")
                
                // Don't highlight the boundary if that's already the row's current position
                boolean noHighlight = (dropAbove && fromIdx == idx - 1) || (!dropAbove && fromIdx == idx + 1)           
                if (noHighlight) return
                
                // Setting highlighting          
                def neighborIdx = dropAbove ? idx - 1 : idx + 1
                def neighborCell = (neighborIdx in 0..maxIdx) ? (visibleCells.find { it.getIndex() == neighborIdx }) : null
                
                int halfBorderWidth = 2
                rowCell.setStyle( edgeStyle( dropAbove, !dropAbove, neighborCell ? halfBorderWidth : halfBorderWidth * 2 ) )
                neighborCell?.setStyle( edgeStyle( !dropAbove, dropAbove, halfBorderWidth ) )
            }
            
            // The add state button, under the "State" labels
            def addBtn = new Button( " Add state" )
            addIcon( addBtn, FontAwesome.Glyph.PLUS, 12 )
            addBtn.setMaxWidth( Double.MAX_VALUE )
            addBtn.setOnAction { addRow( tempStateOrder.size() ) }
            setTT( addBtn, "Add another state (max ${maxClassifierStates})" )
            explainWhenDisabled( addBtn ) { "Already at the most states (${maxClassifierStates})" }
            classColorGrid.add( addBtn, 0, 2 )
            
            // Closure: Update left hand box with "State" labels when class/color list changes
            refreshStateLabelBox = {
                stateLabelBox.getChildren().clear()
                
                def rowHeight = rowListView.getFixedCellSize()
                
                // Making, formatting and placing each "state" label
                for (idx in 1..tempStateOrder.size()) {
                    def labelText = idx == 1 ? "(Default)  State 1:" : "State ${idx}:"
                    def label = new Label( labelText )
                    label.setMinHeight( rowHeight )
                    label.setPrefHeight( rowHeight )
                    label.setMaxHeight( rowHeight )
                    label.setMaxWidth( Double.MAX_VALUE )
                    label.setAlignment( Pos.CENTER_RIGHT )
                    stateLabelBox.getChildren().add( label )
                }
                
                addBtn.setDisable( tempStateOrder.size() >= maxClassifierStates )
                
                // Resize list + label column
                def listHeight = tempStateOrder.size() * (rowHeight - 1) + 8
                rowListView.setPrefHeight( listHeight )
                stateLabelBox.setPrefHeight( listHeight )
                stateLabelBox.setMaxHeight( listHeight )
                stateLabelBox.setMinHeight( listHeight )
                
                // Making sure overall window fits UI layout
                refitPopout( classColorDialog )
            }
            
            refreshStateLabelBox()
            
            
            // The add button and each row's remove button, for the help entry that covers them
            def minusBtnByRow = [:]
            def addRemoveBtns = [addBtn]
            
            // Setup row list of classes/colors and dragging behavior
            rowListView.setCellFactory { lv ->
                
                // Define/build the row with class/color
                def rowCell = new ListCell<Integer>() {
                    
                    @Override
                    protected void updateItem( Integer item, boolean empty ) {
                        super.updateItem( item, empty )
                        
                        if (empty || item == null || item < 0 || item >= stateBoxes.size()) {
                            setText( null )
                            setGraphic( null )
                            setStyle( "" )
                            return
                        }
                        
                        int idx = getIndex()
                        
                        def dragHandle = new Label( "≡" )
                        dragHandle.setPadding( new Insets( 5, 0, 0, 0 ) )
                        dragHandle.setMinWidth( 5 )
                        setTT( dragHandle, "Drag to reorder states/colors" )
                        
                        def minusBtn = new Button()
                        addIcon( minusBtn, FontAwesome.Glyph.MINUS, 10 )
                        minusBtn.setMinWidth( 28 )
                        minusBtn.setPrefWidth( 28 )
                        minusBtn.setOnAction { removeRow( idx ) }
                        // Rows are rebuilt as they change, so the help entry's list is kept up to date here
                        minusBtnByRow[ idx ] = minusBtn
                        addRemoveBtns.clear()
                        addRemoveBtns.add( addBtn )
                        addRemoveBtns.addAll( minusBtnByRow.values() )
                        minusBtn.setDisable( tempStateOrder.size() <= minClassifierStates )
                        setTT( minusBtn, "Remove state (min ${minClassifierStates})" )
                        explainWhenDisabled( minusBtn ) { "Already at the fewest states (${minClassifierStates})" }
                        
                        // The remove button sits at the end of the row, clear of the settings
                        def rowSpacer = new Region()
                        HBox.setHgrow( rowSpacer, Priority.ALWAYS )
                        
                        def row = new HBox( 10 )
                        row.setPadding( new Insets( 2, 0, 2, 0 ) )
                        row.getChildren().addAll(
                            dragHandle,
                            stateBoxes[ idx ],
                            colorPickers[ idx ],
                            reviewerCheckboxes[ idx ],
                            rowSpacer,
                            minusBtn
                        )
                        
                        setText( null )
                        setGraphic( row )
                    }
                }
                
                // When a row is grabbed to drag, records the index of the row
                rowCell.setOnDragDetected { e ->
                    if (rowCell.isEmpty()) return
                    
                    def db = rowCell.startDragAndDrop( TransferMode.MOVE )
                    def cc = new ClipboardContent()
                    cc.putString( rowCell.getIndex().toString() )
                    db.setContent( cc )
                    
                    def snapshot = rowCell.snapshot( new SnapshotParameters(), null )
                    db.setDragView( snapshot, e.getX(), e.getY() )
                    
                    e.consume()
                }
                
                // When a row is dragged over another row, reporting the index of the other row
                rowCell.setOnDragOver { e ->
                    if (e.getGestureSource() != rowCell && e.getDragboard().hasString()) {
                        e.acceptTransferModes( TransferMode.MOVE )
                        
                        double y = e.getY()
                        double h = rowCell.getHeight()
                        boolean dropAbove = y < h / 2.0
                        
                        int fromIdx = Integer.parseInt( e.getDragboard().getString() )
                        showMarker( rowCell, dropAbove, fromIdx, lv )
                    }
                    e.consume()
                }
                
                // When row dragging exits pane of cells, stops highlighting
                rowCell.setOnDragExited { e ->
                    clearMarker( lv )
                    e.consume()
                }
                
                // When row dragging stops, making any needed changes
                rowCell.setOnDragDropped { e ->
                    if (!e.getDragboard().hasString()) {
                        e.setDropCompleted( false )
                        e.consume()
                        return
                    }
                    
                    int fromIdx = Integer.parseInt( e.getDragboard().getString() )
                    int thisIdx = rowCell.getIndex()
                    
                    double y = e.getY()
                    double h = rowCell.getHeight()
                    boolean dropAbove = y < h / 2.0
                    int toIdx = dropAbove ? thisIdx : thisIdx + 1
                    
                    // Moving row in reference lists/maps
                    moveRow( fromIdx, toIdx )
                    refreshRowItems()
                    
                    // Clearing highlighting hint formatting
                    clearMarker( lv )
                    e.setDropCompleted( true )
                    e.consume()
                }
                
                return rowCell
            }
            
            // Setup to restore default number and order of classes/colors
            restoreDefaultsBtn.setOnAction {
                
                tempStateOrder       = new ArrayList<>( defaultStateOrderAndConfirmedColors.keySet() )
                tempConfirmedColors  = new LinkedHashMap<>( defaultStateOrderAndConfirmedColors )
                tempRevStates        = new ArrayList<>( defaultReviewerStates )
                
                // Refilled, not replaced: the help entries point at these lists
                stateBoxes.clear();         stateBoxes.addAll( defaultStateOrderAndConfirmedColors.collect { createStateBox() } )
                colorPickers.clear();       colorPickers.addAll( defaultStateOrderAndConfirmedColors.collect { createColorPicker() } )
                reviewerCheckboxes.clear(); reviewerCheckboxes.addAll( defaultStateOrderAndConfirmedColors.collect { createRevCB() } )
                
                formatControls()
                syncControlsFromMaps()
                refreshRowItems()
                rowListView.refresh()
                refreshStateLabelBox()
                updateOkButton()
            }
            
            
            formatControls()
            syncControlsFromMaps()
            updateOkButton()
            
            
            // "?" button, as in the main window
            addHelpButton( classColorDialog, "States & colors",
                "The subclasses (states) that annotations can be assigned, the order they cycle through in Classifier mode, and the color each is drawn in. Changes are only kept if you press OK.",
                [
                    [ title: "States",
                      nodes: stateBoxes,
                      text : "The name of each state (up to 15 letters and numbers). Pick one from the dropdown or type your own." +
                             "\nPicking a name another row already has swaps the two." +
                             "\nReorder: drag a row by its ≡ handle. State 1 is the default that every annotation starts as.",
                      now  : {
                          List names = stateBoxes.collect { it.getValue() }.findAll()
                          "${some( names.size(), 'state', 'states' )}: ${listOut( names )}. Annotations default to ${names[ 0 ]}."
                      } ],
                    [ title: "Color",
                      nodes: colorPickers,
                      text : "The color an annotation is drawn in once it is assigned that state. Each state needs its own color, so a color already in use can't be picked." ],
                    [ title: "Reviewer classes",
                      nodes: reviewerCheckboxes,
                      text : "The states a reviewer can assign in Reviewer mode (${minReviewerStates} to ${maxReviewerStates}). Classifiers can always assign every state.",
                      now  : {
                          List chosen = (0..<stateBoxes.size()).findAll { reviewerCheckboxes[ it ].isSelected() }.collect { stateBoxes[ it ].getValue() }
                          chosen.size() < minReviewerStates ? "Select at least ${minReviewerStates} (${chosen.size()} selected)." :
                          chosen.size() > maxReviewerStates ? "Select no more than ${maxReviewerStates} (${chosen.size()} selected)." :
                                                              "A reviewer can assign ${listOut( chosen )}."
                      } ],
                    [ title: "Add state, −",
                      nodes: addRemoveBtns,
                      text : "Add state: adds a state to the bottom of the list, with the first unused name and color." +
                             "\n(−) button: removes that row's state." +
                             "\n${minClassifierStates} to ${maxClassifierStates} states allowed.",
                      now  : { "${some( stateBoxes.size(), 'state', 'states' )} in use (${maxClassifierStates} max)." } ],
                    [ title: "Restore defaults",
                      nodes: [restoreDefaultsBtn],
                      text : "Resets the states, their order, colors and reviewer classes to the defaults (${listOut( defaultStateOrder )})." ],
                    [ title: "OK, Cancel",
                      nodes: [okButton, classColorDialog.getDialogPane().lookupButton( ButtonType.CANCEL )],
                      text : "OK: keeps these settings for this session and the next." +
                             "\nCancel: closes the window without changing anything." +
                             "\nOK is greyed out while something needs fixing: hover over it to see what.",
                      now  : {
                          List missing = okButtonBlockers().findAll { it.value }.collect { it.key }
                          missing ? "Not ready. ${missing.join( '. ' )}." : "Ready."
                      } ]
                ] )
                
            def classColorResult = showPopout( classColorDialog )
            
            // Only commit states/colors to map if OK is pressed in popout window
            if (classColorResult.get() == ButtonType.OK) {
                lockedStateOrder = new ArrayList<>( tempStateOrder )
                
                lockedConfirmedColors = [:]
                tempStateOrder.each { stateName ->
                    lockedConfirmedColors[ stateName ] = tempConfirmedColors[ stateName ] ?: Color.GRAY
                }
                
                lockedReviewerStates   = tempRevStates         
                rebuildStateColorMap()
                refreshSettingsSummary()
            }
        }
        
        
        // --- Creating popout to select specific annotations classification criteria to review ---
        session.frequencies = ["different": "with differing subclassifications.", 
                               "all": "subclassified by ALL as",
                               "any": "subclassified by ANY as"]
        
        int numRevRows = session.frequencies.size()
        
        def defaultReviewAnnoFreqs = [ "different", "any" ]
        def defaultReviewAnnos     = [ null, "maybe" ]
        
        def frequencySelectors = []
        def stateSelectors = []
        def switches = []
        (1..numRevRows).each {
            def fS = new ComboBox<String>()
            frequencySelectors << fS
            
            def sS = new ComboBox<String>()
            stateSelectors << sS
            
            def newSwitch = makeSwitch( true )
            switches << newSwitch
        }
        
        // Closure: The conditions in use, in a few words each. committed reads what was last confirmed with
        // OK, otherwise what the popout is showing right now
        def reviewKinds = { boolean committed ->
            (0..<numRevRows).findAll { committed ? switches[ it ].node.getUserData() : switches[ it ].status.get() }.collect { row ->
                String how   = committed ? frequencySelectors[ row ].getUserData() : frequencySelectors[ row ].getValue()
                String state = (committed ? stateSelectors[ row ].getUserData() : stateSelectors[ row ].getValue()) ?: states[ 0 ]
                how == null                               ? null :
                how == session.frequencies[ "different" ] ? "differing subclassifications" :
                how == session.frequencies[ "all" ]       ? "ALL as ${state}" : "ANY as ${state}"
            }.findAll()
        }
        
        // Closure: Bring the preview lines at the bottom of the main window up to date
        refreshSettingsSummary = {
            // Classifiers can give every state, a reviewer only the ones ticked for reviewers
            boolean reviewing = reviewerModeBtn.isSelected()
            
            // The lines read as plain sentences saying what will happen, with what it applies to underneath:
            //   Classifier Mode:  Subclassify Vessel annotations for CAA into either:     ("each Vessel annotation" one at a time)
            //                         [states, with arrows for the order a click moves through them]
            //   Reviewer Mode:    Review Vessel annotations for CAA whose subclassifications:
            //                         [criteria, joined with "or"]
            //                         From: [classifiers]
            //                     Then subclassify them into either:
            //                         [reviewer states]
            String name = classifyingNameSelection.getEditor().getText()?.trim()
            String cls = annoClassField.getValue()?.name
            String forName = name ? " for ${name}" : ""
            String what = (cls ? "${cls} annotations" : "annotations") + forName
            String eachOne = (cls ? "each ${cls} annotation" : "each annotation") + forName
            
            // A review looks at every classifier in the Classifier list. There can be any number of them, so
            // they are only named when that stays short
            List classifiers = (classifierOptions ?: []) as List
            String whose = listOut( classifiers ).length() <= 40 ? listOut( classifiers ) :
                                                                            "all ${classifiers.size()} classifiers"
            
            boolean oneByOne = methodChoice.getValue() == oneByOneMethod
            statesPrefix.setText( reviewing ? "Then subclassify them into either:" : "Subclassify ${oneByOne ? eachOne : what} into either:" )
            reviewPrefix.setText( "Review ${what} whose subclassifications:" )
            reviewWhose.setText( "From: ${classifiers ? whose : 'no classifiers yet'}" )
            List rowOrder = reviewing ? [reviewRow, statesRow] : [statesRow, reviewRow]
            if (settingsBox.getChildren() as List != rowOrder) { settingsBox.getChildren().setAll( rowOrder ) }
            
            List shownStates = (lockedStateOrder as List).findAll { !reviewing || it in lockedReviewerStates }
            // A classifier works through the states in order (each click moves an annotation on to the next), so
            // there they are shown as a sequence, with an arrow from each to the one after it. A reviewer picks
            // a state directly, so there they are just listed
            classesSummary.setHgap( reviewing ? 14 : 6 )
            List chips = []
            shownStates.eachWithIndex { stateName, i ->
                def swatch = new Rectangle( 10, 10, lockedConfirmedColors[ stateName ] as Color )
                swatch.setStroke( Color.GRAY )
                def chip = new Label( stateName as String, swatch )
                chip.setMinWidth( Region.USE_PREF_SIZE )
                if (!reviewing && i < shownStates.size() - 1) {
                    def arrow = new Label( "→" )
                    arrow.setOpacity( 0.6 )
                    def step = new HBox( 6, chip, arrow ) // a state keeps its arrow when the line wraps
                    step.setAlignment( Pos.CENTER_LEFT )
                    chips << step
                } else {
                    chips << chip
                }
            }
            classesSummary.getChildren().setAll( chips )
            setTT( [statesPrefix, classesSummary], reviewing ?
                "The states a reviewer can assign, out of ${listOut( lockedStateOrder as List )}. Set in States & colors." :
                "The states a classifier can assign. Annotations start as ${(lockedStateOrder as List)[ 0 ]}, and each left-click moves one on to the next state, in the order of the arrows. Set in States & colors." )
            // The criteria as plain phrases finishing that sentence, any one of which is enough
            List phrases = (0..<numRevRows).findAll { switches[ it ].node.getUserData() }.collect { row ->
                String freq  = frequencySelectors[ row ].getUserData()
                String state = stateSelectors[ row ].getUserData() ?: states[ 0 ]
                freq == null                               ? null :
                freq == session.frequencies[ "different" ] ? "differ between classifiers" :
                freq == session.frequencies[ "all" ]       ? "are all ${state}" : "include ${state}"
            }.findAll()
            String criteria = phrases.join( ", or " )
            reviewSummary.setText( criteria )
            setTT( [reviewPrefix, reviewSummary, reviewWhose], "Annotations are reviewed if their subclassifications ${criteria}. Set in Review criteria.\n" +
                   (classifiers ? "Classifiers looked at: ${listOut( classifiers )} (everyone in the Classifier list)." :
                                  "No classifiers in the Classifier list yet.") )
            reviewRow.setVisible( reviewing )
            reviewRow.setManaged( reviewing )
            fitPreviews()
        }
        modeButtons.selectedToggleProperty().addListener { obs, oldToggle, newToggle -> refreshSettingsSummary() }
        annoClassField.valueProperty().addListener { obs, oldVal, newVal -> refreshSettingsSummary() }
        methodChoice.valueProperty().addListener { obs, oldVal, newVal -> refreshSettingsSummary() }
        classifyingNameSelection.getEditor().textProperty().addListener { obs, oldVal, newVal -> refreshSettingsSummary() }
        
        specifyReviewAnnosBtn.setOnAction {
            def specifyAnnosDialog = new Dialog<ButtonType>()
            specifyAnnosDialog.setTitle( "Set annotations to review from ${classifierOptions}" )
            
            specifyAnnosDialog.getDialogPane().getButtonTypes().setAll(
                ButtonType.OK,
                ButtonType.CANCEL
            )
            
            def okButton = specifyAnnosDialog.getDialogPane().lookupButton( ButtonType.OK )
            
            def restoreReviewerAnnoDefaultsBtn = new Button( " Restore defaults" )
            setTT( restoreReviewerAnnoDefaultsBtn, "Restore reviewer criteria to default." )
            addIcon( restoreReviewerAnnoDefaultsBtn, FontAwesome.Glyph.UNDO, 15 )
            
            def frequencySelectorLabel = new Label( "Frequency:" )
            frequencySelectorLabel.setPadding( new Insets( 5 ) )  
            frequencySelectors.each { it.getItems().setAll( session.frequencies.values() ) } // setAll: the selectors outlive the dialog, so reopening it must not add the options again
            frequencySelectors.each { it.setValue( it.getUserData() ) }
            String freqSelectorTTtext = "Select the frequency of an annotation being subclassified as a specific state."
            setTT( [frequencySelectorLabel] + frequencySelectors, freqSelectorTTtext )
            
            def stateSelectorLabel = new Label( "State:" )
            stateSelectorLabel.setPadding( new Insets( 5 ) ) 
            stateSelectors.each { it.getItems().setAll( states ) }
            stateSelectors.each { it.setEditable( true ) }
            stateSelectors.each { it.setPromptText( "Type or select" ) }
            stateSelectors.each { it.setValue( it.getUserData() ) }
            setTT( [stateSelectorLabel] + stateSelectors, "State the subclassifications should have (select or type)" )
            
            def leftSentences = []
            def andLabels = []
            (1..numRevRows).each {
                def lS = new Label( "Review annotations" )
                lS.setPadding( new Insets( 5 ) )
                leftSentences << lS
                
                def aL = new Label( "AND" )
                aL.setPadding( new Insets( 5 ) )
                andLabels << aL
            }
            
            // Closure: Make sure state cells are formatted correctly (disabled and "NA")
            def updateReviewerAnnoRows = {
                stateSelectors.eachWithIndex { stateSelector, i ->
                    if (frequencySelectors[ i ].getValue() == session.frequencies[ "different" ]) {
                        stateSelector.setVisible( false )
                    }
                }
            }        
            updateReviewerAnnoRows()
            
            // Stopping states from being confirmed blank
            stateSelectors.eachWithIndex { stateSelector, i ->            
                stateSelector.valueProperty().addListener { obs, oldVal, newVal ->
                    stateSelector.setValue( newVal ?: oldVal )
                }
            }
            
            // Letters/numbers only and no more than 15 characters
            stateSelectors.each { sS ->
                sS.getEditor().textProperty().addListener { obs, oldVal, newVal ->
                    if (!newVal.matches( "[A-Za-z0-9]*" ) || newVal.length() > 15) {
                        sS.getEditor().setText( oldVal )
                    }
                }
            }
            
            // Hiding state if the frequency selection is for different classifications
            frequencySelectors.eachWithIndex { freqSelector, i ->
                freqSelector.valueProperty().addListener { obs, oldVal, newVal ->
                    stateSelectors[ i ].setVisible( newVal != session.frequencies[ "different" ] )
                }
            }
            
            
            // One row per condition: AND | Review annotations | frequency | state | remove. Rows that aren't
            // in use are hidden, and conditions are added and removed with buttons, like the states
            def specifyAnnosGrid = new GridPane()
            specifyAnnosGrid.setHgap( 10 )
            specifyAnnosGrid.setVgap( 6 )
            specifyAnnosGrid.add( frequencySelectorLabel, 2, 0 )
            specifyAnnosGrid.add( stateSelectorLabel,     3, 0 )
            def rowContainers = [:]
            def minusBtns = []
            
            def addConditionBtn = new Button( " Add condition" )
            addIcon( addConditionBtn, FontAwesome.Glyph.PLUS, 12 )
            setTT( addConditionBtn, "Add another kind of annotation to review (max ${numRevRows})" )
            explainWhenDisabled( addConditionBtn ) { "Already at the most conditions (${numRevRows})" }
            
            // Closure: Show the rows in use and hide the rest
            def showRows = {
                (0..<numRevRows).each { row ->
                    boolean on = row == 0 || switches[ row ].status.get()
                    ([andLabels[ row ], leftSentences[ row ], frequencySelectors[ row ], minusBtns[ row ]].findAll { it != null }).each { node ->
                        node.setVisible( on && !node.is( andLabels[ 0 ] ) ) // the first condition has no AND
                        node.setManaged( on )
                    }
                    stateSelectors[ row ].setManaged( on )
                    stateSelectors[ row ].setVisible( on && frequencySelectors[ row ].getValue() != session.frequencies[ "different" ] )
                }
                addConditionBtn.setDisable( (0..<numRevRows).every { switches[ it ].status.get() } )
                refitPopout( specifyAnnosDialog )
            }
            
            for (i in 0..numRevRows-1) {
                int row = i // its own copy, for the remove button's action
                
                specifyAnnosGrid.add( andLabels[ row ],          0, row + 1 )
                specifyAnnosGrid.add( leftSentences[ row ],      1, row + 1 )
                specifyAnnosGrid.add( frequencySelectors[ row ], 2, row + 1 )
                specifyAnnosGrid.add( stateSelectors[ row ],     3, row + 1 )
                
                if (row == 0) {
                    minusBtns << null // the first condition can't be removed
                    
                } else {
                    def minusBtn = new Button()
                    addIcon( minusBtn, FontAwesome.Glyph.MINUS, 10 )
                    minusBtn.setMinWidth( 28 )
                    minusBtn.setPrefWidth( 28 )
                    setTT( minusBtn, "Remove this condition" )
                    minusBtn.setOnAction {
                        // Later conditions move up a row, then the last one in use is taken off
                        int lastOn = (0..<numRevRows).findAll { switches[ it ].status.get() }.max()
                        (row..<lastOn).each { r ->
                            frequencySelectors[ r ].setValue( frequencySelectors[ r + 1 ].getValue() )
                            stateSelectors[ r ].setValue( stateSelectors[ r + 1 ].getValue() )
                        }
                        switches[ lastOn ].status.setValue( false )
                        showRows()
                    }
                    minusBtns << minusBtn
                    specifyAnnosGrid.add( minusBtn, 4, row + 1 )
                    
                    rowContainers[ switches[ row ].node ] = [andLabels[ row ], leftSentences[ row ], frequencySelectors[ row ], stateSelectors[ row ]]
                }
            }
            
            addConditionBtn.setOnAction {
                int firstOff = switches.findIndexOf { !it.status.get() }
                if (firstOff < 0) { return }
                // A second "differing classifications" condition would add nothing, so the new row starts on another
                def inUse = (0..<firstOff).collect { frequencySelectors[ it ].getValue() }
                if (frequencySelectors[ firstOff ].getValue() == session.frequencies[ "different" ] && session.frequencies[ "different" ] in inUse) {
                    frequencySelectors[ firstOff ].setValue( session.frequencies[ "any" ] )
                }
                switches[ firstOff ].status.setValue( true )
                showRows()
            }
            
            def specifyAnnosBox = new VBox( 10, restoreReviewerAnnoDefaultsBtn, specifyAnnosGrid, addConditionBtn )
            specifyAnnosBox.setPadding( new Insets( 10 ) )
            specifyAnnosDialog.getDialogPane().setContent( specifyAnnosBox )
            
            // Set initial switches based on previous settings
            switches.each { rowSwitch ->
                boolean isOn = rowSwitch.node.getUserData()
                rowSwitch.status.setValue( isOn )
                rowContainers[ rowSwitch.node ].each { it.setDisable( !isOn ) }
            }
            
            // Disabling row and subsequent switches/rows if turned off using switch
            switches.eachWithIndex { rowSwitch, i ->
                rowSwitch.status.addListener { boolProp, oldVal, newVal ->
                    
                    rowContainers[ rowSwitch.node ].each { it.setDisable( !newVal ) }
                    
                    def nextSwitch = (i < switches.size() - 1) ? switches[ i+1 ] : null
                    nextSwitch?.status?.setValue( false )
                    nextSwitch?.node?.setDisable( !newVal )
                }
            }
            
            // The state box comes and goes with the frequency, which also changes what's on show
            def rowsFollowFrequency = { obs, oldVal, newVal -> showRows() } as javafx.beans.value.ChangeListener
            frequencySelectors.each { it.valueProperty().addListener( rowsFollowFrequency ) }
            showRows()
            
            // Making sure there are no empty state names in the rows in use
            okButton.disableProperty().bind(
                stateSelectors.collect { it.getEditor().textProperty().isEmpty().and( it.visibleProperty() ).and( it.disabledProperty().not() ) }
                    .inject { a, b -> a.or( b ) }
            )
            explainWhenDisabled( okButton ) { "Choose a state for every condition" }
            
            // When the restore defaults button is pressed
            restoreReviewerAnnoDefaultsBtn.setOnAction {     
                
                def revStateFreqs = defaultReviewAnnoFreqs
                def revStates     = defaultReviewAnnos 
                
                for (i in 0..numRevRows-1) {
                    
                    def freqKey = revStateFreqs[ i ]
                    def freqValue = session.frequencies[ freqKey ]
                    def state = revStates[ i ]
                    
                    if (freqValue) {
                        frequencySelectors[ i ].setValue( freqValue )
                        stateSelectors[ i ].setValue( state ?: states[ 0 ] )
                    }
                    
                    switches[ i ].status.setValue( freqValue ? true : false )
                }
                
                updateReviewerAnnoRows()
                showRows()
            }
            
            // Blocking nodes from becoming highlighted when clicked
            disableNonEditableFocusTraversal( specifyAnnosDialog.getDialogPane() )
            
            // "?" button, as in the main window
            addHelpButton( specifyAnnosDialog, "Review criteria",
                "Which annotations a reviewer will see, out of those every classifier has completed. Each row is one condition: an annotation that meets any of them is reviewed. Changes are only kept if you press OK.",
                [
                    [ title: "Frequency",
                      nodes: [frequencySelectorLabel] + leftSentences + frequencySelectors,
                      text : "How the classifiers' subclassifications must compare for an annotation to be reviewed." +
                             "\n'with differing subclassifications': the classifiers disagree." +
                             "\n'subclassified by ANY as': at least one classifier assigned the state." +
                             "\n'subclassified by ALL as': every classifier assigned the state.",
                      now  : { "Reviewing annotations with ${reviewKinds( false ).join( ', plus ' )}." } ],
                    [ title: "State",
                      nodes: [stateSelectorLabel] + stateSelectors,
                      text : "The state the ANY and ALL conditions look for (up to 15 letters and numbers). Pick one from the dropdown or type your own." +
                             "\nNot needed for differing subclassifications, so it's hidden." ],
                    [ title: "Add condition, −",
                      nodes: [addConditionBtn] + minusBtns.findAll(),
                      text : "Add condition: adds a row (${numRevRows} max)." +
                             "\n(−) button: removes that row's condition. The first row always stays.",
                      now  : { "${some( (0..<numRevRows).count { switches[ it ].status.get() } as int, 'condition', 'conditions' )} in use (${numRevRows} max)." } ],
                    [ title: "Restore defaults",
                      nodes: [restoreReviewerAnnoDefaultsBtn],
                      text : "Resets the conditions to the defaults: annotations with differing subclassifications, plus any that a classifier assigned ${defaultReviewAnnos[ 1 ]}." ],
                    [ title: "OK, Cancel",
                      nodes: [okButton, specifyAnnosDialog.getDialogPane().lookupButton( ButtonType.CANCEL )],
                      text : "OK: keeps these conditions for this session and the next." +
                             "\nCancel: closes the window without changing anything." +
                             "\nOK is greyed out while a condition has no state.",
                      now  : { okButton.isDisabled() ? "Not ready. Choose a state for every condition." : "Ready." } ]
                ] )
                
            def specifyAnnosResult = showPopout( specifyAnnosDialog )
            frequencySelectors.each { it.valueProperty().removeListener( rowsFollowFrequency ) }
            
            // Only commit states/colors to map if OK is pressed in popout window
            if (specifyAnnosResult.get() == ButtonType.OK) {
                
                frequencySelectors.each { it.setUserData( it.getValue() ) }
                stateSelectors.each     { it.setUserData( it.getValue() ) }
                switches.each           { it.node.setUserData( it.status.getValue() ) } 
                refreshSettingsSummary()
            }
        }
        
        
        // -- Defining closure that sets preferences based on map --
        def setPreferencesFromMap = { pMap ->
            def nonUpdatedPrefs = []
            
            def updatePrefsOrLog = { check, primaryAction, fallbackAction, String prefName ->
                check ? primaryAction() : fallbackAction()
                if (!check) { nonUpdatedPrefs << prefName }
            }
            
            boolean classifierMode = pMap.mode != ActionStrings.REVIEWER_MODE // default = classifier
            
            updatePrefsOrLog(
                pMap.mode in [ActionStrings.CLASSIFIER_MODE, ActionStrings.REVIEWER_MODE],     
                { classifierMode ? classifierModeBtn.setSelected( true ) : reviewerModeBtn.setSelected( true ) },
                { classifierModeBtn.setSelected( true ) },
                "Mode"
            )
            
            updatePrefsOrLog(
                pMap.classifiers instanceof List && pMap.classifiers.every { it instanceof String },
                { classifierOptions = FXCollections.observableArrayList( pMap.classifiers ) },
                { classifierOptions = classifierOptions ?: FXCollections.observableArrayList() },
                "Classifier options"
            )
            
            updatePrefsOrLog(
                pMap.reviewers instanceof List && pMap.reviewers.every { it instanceof String },
                { reviewerOptions = FXCollections.observableArrayList( pMap.reviewers ) },
                { reviewerOptions = reviewerOptions ?: FXCollections.observableArrayList() },
                "Reviewer options"
            )
            
            def userOptions = classifierMode ? classifierOptions : reviewerOptions
            
            updatePrefsOrLog(
                pMap.userName instanceof String,
                { pMap.userName in userOptions ?: userOptions.addFirst( pMap.userName );
                  users.setValue( pMap.userName ) },
                { users.setValue( users.getItems()?.isEmpty() ? null : users.getItems()?.first() ) },
                "Username"
            )
            
            users.setItems( classifierMode ? classifierOptions : reviewerOptions )
            
            updatePrefsOrLog(
                annoClassField.getItems().find { it.name == pMap.annoClass },
                { annoClassField.setValue( annoClassField.getItems().find { it.name == pMap.annoClass } ) },
                { annoClassField.setValue( annoClassField.getItems()[0] ) },
                "Annotation class"
            )
            
            updatePrefsOrLog(
                pMap.classifyingName instanceof String,
                { classifyingNameSelection.setValue( pMap.classifyingName.toString() ) },
                { classifyingNameSelection.setValue( "" ) },
                "Subclassification name"
            )
            
            updatePrefsOrLog(
                pMap.recentClassifyingNames instanceof List && pMap.recentClassifyingNames.every { it instanceof String },
                { classifyingNameOptions = FXCollections.observableArrayList( pMap.recentClassifyingNames?.unique() );
                  classifyingNameSelection.setItems( classifyingNameOptions ) },
                { classifyingNameOptions = classifyingNameOptions ?: FXCollections.observableArrayList() },
                "Subclassification name options"
            )
            
            updatePrefsOrLog(
                pMap.gridSize instanceof Number,
                { gridSizeField.setText( pMap.gridSize.intValue().toString() ) },
                { gridSizeField.setText( "500" ) },
                "Grid size"
            )
            // Settings saved before there was a choice of method have none: they mean the grid
            methodChoice.setValue( pMap.singleObjects == true ? oneByOneMethod : byGridMethod )
            
            updatePrefsOrLog(
                pMap.selectedChannels instanceof List && channelCheckboxes.collect { it.getText() }.containsAll( pMap.selectedChannels ),
                { channelCheckboxes.each { it.setSelected( false ) }; 
                  channelCheckboxes.each { it.setSelected( it.getText() in pMap.selectedChannels ) } },
                { channelCheckboxes.each { it.setSelected( false ) } },
                "Selected channels"
            )
            
            
            // Setting reviewer annotation selections
            def revStateFreqs = pMap.reviewStateFreqs instanceof List ? pMap.reviewStateFreqs : defaultReviewAnnoFreqs
            def revStates     = pMap.reviewStates instanceof List     ? pMap.reviewStates     : defaultReviewAnnos
            
            for (i in 0..numRevRows-1) {
                def freqKey = revStateFreqs[ i ]
                def freqValue = session.frequencies[ freqKey ]
                def state = revStates[ i ]
                
                frequencySelectors[ i ].setUserData( freqValue ?: session.frequencies.values()[ 0 ] )
                stateSelectors[ i ].setUserData( freqValue ? state ?: states[ 0 ] : states[ 0 ] )
                switches[ i ].node.setUserData( freqValue ? true : false )
            }
            
            updatePrefsOrLog(
                pMap.reviewStateFreqs instanceof List && pMap.reviewStates instanceof List,
                { null },
                { null },
                "Reviewer annotation preferences"
            )
            
            // Setting classification states, order, and colors
            def savedStates = pMap.statesAndColors?.state
            lockedStateOrder = savedStates ? savedStates.unique() : defaultStateOrderAndConfirmedColors.keySet()
            
            def missingStates = lockedStateOrder.findAll { !(it in states) }
            if (missingStates) {
                states.addAll( missingStates )
            }
            
            lockedConfirmedColors = [:]
            lockedStateOrder.eachWithIndex {stateName, idx ->
                def savedColor = pMap.statesAndColors?[ idx ]?.color
                lockedConfirmedColors[ stateName ] =
                    savedColor instanceof Number ?
                        intToFx( savedColor as int ) :
                        (defaultStateOrderAndConfirmedColors[ stateName ] ?: Color.GRAY)
            }
            
            if (!savedStates) {
                nonUpdatedPrefs << "Subclassification states/order/colors"
            }
            
            // Setting reviewer-available states
            def savedReviewerStates = pMap.statesAndColors?.findAll { it.revState }?.state
            lockedReviewerStates = savedReviewerStates ? savedReviewerStates.unique() : defaultReviewerStates
            
            if (!savedReviewerStates ) {
                nonUpdatedPrefs << "Reviewer subclassification states"
            }
            
            
            rebuildStateColorMap()
            refreshSettingsSummary()
            
            if (firstPrompt) {
                if (pMap.size() > 1L) {
                    if (nonUpdatedPrefs.isEmpty()) {
                        Dialogs.showInfoNotification( "", "Successfully updated preferences." )
                    
                    } else {
                        String notification = nonUpdatedPrefs.collect { "  - $it" }.join( "\n" )
                        Dialogs.showWarningNotification( "", "UNABLE TO UPDATE: \n${notification}" )
                    }
                }
            }
        }
        
        setPreferencesFromMap( prePrefsMap )
        
        
        
        // Closure to collect current preferences and make map
        def collectCurrentPrefs = {
            def userName          = users.getValue().capitalize()
            def mode              = session.classifierMode ? ActionStrings.CLASSIFIER_MODE : ActionStrings.REVIEWER_MODE
            def checkingWork      = checkingWorkSwitch.status.get()
            def selectedChannels  = channelCheckboxes.findAll { it.isSelected() }
                                        .collect { it.getText() }
            
            def annoClass               = annoClassField.getValue().name
            def classifyingName         = classifyingNameSelection.getValue()?.trim()
            List recentClassifyingNames = ([classifyingName] + classifyingNameOptions).unique().take( 5 ) // a copy: Export reads this too, and the dropdown keeps all its names
            def gridSizeText            = gridSizeField.getText().trim()
            boolean singleObjects       = methodChoice.getValue() == oneByOneMethod
            def gridSize                = gridSizeText ? Integer.parseInt( gridSizeText ) : 500 // can be blank when one at a time
            boolean instructions        = prePrefsMap.instructions != null ? prePrefsMap.instructions : true
            
            // Reviewer annotation preferences
            def reviewStateFreqs = []
            def reviewStates = []
            
            for (i in 0..numRevRows-1) {         
                if (switches[ i ].node.getUserData()) {
                    reviewStateFreqs << session.frequencies.find { it.getValue() == frequencySelectors[ i ].getUserData() }.getKey()
                    reviewStates << (stateSelectors[ i ].getUserData() ?: states[ 0 ])
                }
            }
            
            def prefMap = [
                "userName"               : userName,
                "classifiers"            : classifierOptions,
                "reviewers"              : reviewerOptions,
                "mode"                   : mode,
                "checkingWork"           : checkingWork,
                "selectedChannels"       : selectedChannels,
                "annoClass"              : annoClass,
                "classifyingName"        : classifyingName,
                "recentClassifyingNames" : recentClassifyingNames,
                "gridSize"               : gridSize,
                "singleObjects"          : singleObjects,
                "statesAndColors"        : stateColorMap,
                "reviewStateFreqs"       : reviewStateFreqs,
                "reviewStates"           : reviewStates,
                "instructions"           : instructions
            ]
            
            return prefMap
        }
        
        // Exporting current preferences to file
        exportPrefsBtn.addEventFilter( ActionEvent.ACTION ) { e ->
            e.consume() // prevent dialog closing
            
            def fileChooser = new FileChooser()
            fileChooser.setTitle( "Export preferences file" )
            fileChooser.setInitialDirectory( projectDirectory )
            fileChooser.setInitialFileName( "subclassify-preferences.json" )
            fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter( "JSON files", "*.json" )
            )
            
            def owner  = preferencesDialog.getDialogPane().getScene()?.getWindow()
            def newPrefsFile = fileChooser.showSaveDialog( owner )
            
            if (newPrefsFile != null) {
                def choiceFileName = newPrefsFile.getName()
                
                def activePreferences = collectCurrentPrefs()
                writePreferencesMaptoJSON( newPrefsFile, activePreferences )
                
                Dialogs.showInfoNotification( "", "Preferences saved to \"${choiceFileName}\"" )
                preferencesDialog.getDialogPane().getScene().getWindow().toFront()
            }
        }
        
        // Importing saved preferences file
        importPrefsBtn.addEventFilter( ActionEvent.ACTION ) { e ->
            e.consume() // prevent dialog closing
            
            def fileChooser = new FileChooser()
            fileChooser.setTitle( "Select file to import preferences from:" )
            fileChooser.setInitialDirectory( projectDirectory )
            fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter( "JSON Files", "*.json" )
            )
            def owner  = preferencesDialog.getDialogPane().getScene()?.getWindow()
            def fileChoice = fileChooser.showOpenDialog( owner )
            
            if (fileChoice != null) {
                def newPreferencesMap = readJSONtoPreferencesMap( fileChoice )
                setPreferencesFromMap( newPreferencesMap )           
                
                preferencesDialog.getDialogPane().getScene().getWindow().toFront()
            }
        }
        
        
        // - Setting buttons to not be highlighted when clicked -
        disableNonEditableFocusTraversal( preferencesDialog.getDialogPane() )
        
        
        // - Disabling OK button unless certain criteria is met -
        def anySelectedChannel = Bindings.createBooleanBinding(
            { channelCheckboxes.any { it.isSelected() } },
            *channelCheckboxes.collect { it.selectedProperty() }
        )
        okBtn.disableProperty().bind(
            classifyingNameSelection.getEditor().textProperty().isEmpty()
            .or( users.valueProperty().isNull() )
            .or( gridSizeField.textProperty().isEmpty().and( gridSizeField.disabledProperty().not() ) )
            .or( anySelectedChannel.not() )
            .or( annoClassField.valueProperty().isNull() )
        )

        // Red outline on each input while it is a reason the "OK" button is disabled
        flagWhenBlocking( users, users.valueProperty().isNull() )
        flagWhenBlocking( annoClassField, annoClassField.valueProperty().isNull() )
        flagWhenBlocking( classifyingNameSelection, classifyingNameSelection.getEditor().textProperty().isEmpty() )
        flagWhenBlocking( fullChannelsBox, anySelectedChannel.not() )

        // Say why on hover, while disabled
        explainWhenDisabled( okBtn ) { [
            ("Select a ${classifierModeBtn.isSelected() ? 'classifier' : 'reviewer'}" as String): users.getValue() == null,
            "Select an annotation class"  : annoClassField.getValue() == null,
            "Enter a subclassification name"    : !classifyingNameSelection.getEditor().getText(),
            "Enter a grid size"           : !gridSizeField.getText() && !gridSizeField.isDisabled(),
            "Select at least one channel" : !anySelectedChannel.get()
        ] }

        // "?" button: what each input does. Each entry's now: says how that input stands at the moment, read
        // from the dialog every time the help panel refreshes
        def modeWord = { classifierModeBtn.isSelected() ? "classifier" : "reviewer" }

        addHelpButton( preferencesDialog, "Manual subclassification",
            "Lets one or more people subclassify annotations by eye. A reviewer can then make a final call on the ones they disagree on. Each person's subclassifications are saved separately in the annotations' metadata, which can be saved outside of the project.",
            [
                [ title: "Mode selection",
                  nodes: [classifierModeBtn, reviewerModeBtn],
                  text : "Classifier mode: subclassify ALL annotations of desired class. " +
                         "\nReviewer mode: subclassify SELECT annotations of a class based on previous user subclassifications.",
                  now  : { classifierModeBtn.isSelected() ? "Classifier Mode is selected." : "Reviewer Mode is selected." } ],
                [ title: "User selection",
                  nodes: [userLabel, userRow],
                  oneBox: true,
                  text : "Add user: (+) button > type name > press Enter." +
                         "\nRemove user: (-) button > select user. " +
                         "\nSearch button: fills user list with names used on annotations in the project (will be outlined in green).",
                  now  : { users.getValue() ? "${users.getValue()} is selected as the ${modeWord()}." :
                           !users.getItems()  ? "No ${modeWord()}s available yet. Add one with the (+) button, or use the search button to find names already used in the project." :
                                                "No ${modeWord()} selected yet." } ],
                [ title: "Annotation class",
                  nodes: [annoClassLabel, annoClassField],
                  oneBox: true,
                  text : "The class of annotation to subclassify. The numbers indicate how many annotations of the class are in the image (if open) and the project." + 
                         "\n\nSelect from classes of existing annotations in the project in the dropdown.",
                  now  : {
                      def clsOpts = annoClassField.getItems()
                      def cls = annoClassField.getValue()                      
                      !clsOpts ? "No annotations in the project yet, so there are no classes to choose from. Draw or import annotations first." :
                          cls ? "Annotations of class '${cls.name}' will be subclassified " +
                                (currentImageName ? "(${cls.imageNum} in image, " : "(") +
                                "${cls.num as int} in project)" :
                                "No class selected yet."
                  } ],
                [ title: "Subclassification name",
                  nodes: [classifyingNameLabel, classifyingNameRow],
                  oneBox: true,
                  text : "A short name for what is being judged, such as CAA (up to 15 characters). The subclassifications are saved in the annotation's metadata under a key referencing the mode, user, and subclassification name (such as ${session.toolKeyPrefix}_classifier_User1_CAA)." + 
                         "\n\nType a name or pick a recent one from the dropdown." + 
                         "\nSearch button: fills the list with subclassification names in the metadata of existing annotations in the project.",
                  now  : {
                      String name = classifyingNameSelection.getEditor().getText()
                      name ? "Subclassifications will be saved under ${session.toolKeyPrefix}_${modeWord()}_${users.getValue() ?:'(name)'}_${name}." :
                         !classifyingNameSelection.getItems() ? "No name entered yet, and there are no recent ones available. Type a new one, or use the search button to find names already used in the project." :
                         "No name entered yet. Type one or pick a recent one from the dropdown."
                  } ],
                [ title: "Subclassification method",
                  nodes: [gridSizeLabel, methodRow],
                  oneBox: true,
                  text : "Classifier mode only. Choose how you would like to subclassify your objects." +
                         "\n\n'Grid squares': batch processing. Work through your images along a grid one square at a time. All objects of the selected class that fall within the current square can be subclassified simultaneously. The size of the grid squares can be modified: a bigger grid means fewer squares, with more annotations in each. Left-click on an annotation to cycle its subclassification. Right-click on an annotation to zoom in/out." + 
                         "\n'One at a time': shows a single annotation at a time, zoomed in on it. Left-click to cycle its subclassification.",
                  now  : {
                      if (!classifierModeBtn.isSelected()) { return "Not used in Reviewer Mode." }
                      if (methodChoice.getValue() == oneByOneMethod) { return "Process objects one at a time." }
                      gridSizeField.getText() ? "Process objects in ${gridSizeField.getText()} µm squares." : "No grid size entered yet."
                  } ],
                [ title: "Subclassify new / Check existing",
                  nodes: [checkingWorkBox],
                  text : "Subclassify new: work through objects not yet subclassified." + 
                         "\nCheck existing: go over objects you have already subclassified.",
                  now  : { checkingWorkSwitch.status.get() ? "Set to Check existing, so you'll go back over objects you've completed." :
                                                             "Set to ${newWorkLabel.getText()}, so you'll move through objects you have not yet subclassified." } ],
                [ title: "Reference channels",
                  nodes: [fullChannelsBox],
                  text : "The channels that will help you subclassify your objects (${maxChannels} max). These can be toggled and their brightness changed while you work. Only channels available in all project images are shown.",
                  now  : {
                      List names = channelCheckboxes.findAll { it.isSelected() }*.getText()
                      !channelCheckboxes ? "No channels are shared by every image in the project, so there are none to choose from." :
                      names ? "${listOut( names )} selected (${names.size()} of ${maxChannels} max)." : "No channels selected yet."
                  } ],
                [ title: "Review criteria",
                  nodes: [reviewRow],
                  text : "Reviewer mode only. Review SELECT annotations based on their subclassification(s) from all 'Classifier' users. Here, you can specify which annotations to review based on what those subclassifications should be. For example, you can review annotations with subclassifications that differ, or those that have a specific subclassification (such as 'maybe'). The annotations must have the same Annotation class and Subclassification name." + 
                         "\n\nThe left shows a preview of WHICH annotations will be reviewed. The 'Review criteria' button opens a window to customize that.",
                  now  : {
                      if (classifierModeBtn.isSelected()) { return "Not used in Classifier Mode." }
                      if (!classifierOptions) { return "No classifiers available yet, so there is nothing to review. Subclassify in Classifier Mode first, or use the search button by the Classifier list to find classifiers already in the project." }
                      List kinds = reviewKinds( true )
                      kinds ? "You'll review: ${kinds.join( ', plus ' )}." : null
                  } ],
                [ title: "Subclassification states & colors",
                  nodes: [statesRow],
                  text : "The name, color, and order (classifier mode) of subclasses that you can assign to the chosen objects. They are previewed and can be customized using the 'States & colors' button (${minClassifierStates} to ${maxClassifierStates} states).",
                  now  : {
                      List order = lockedStateOrder as List
                      classifierModeBtn.isSelected() ?
                          "A classifier can assign ${listOut( order )}, and default to ${order[ 0 ]}." :
                          "A reviewer can assign ${listOut( order.findAll { it in lockedReviewerStates } )}, out of ${listOut( order )}."
                  } ],
                [ title: "Export / Import preferences",
                  nodes: [exportPrefsBtn, importPrefsBtn],
                  text : "Helpful for multiple users to have the same settings." ],
                [ title: "Launch",
                  nodes: [okBtn],
                  text : "Starts the session. Works through the project's images with annotations of the chosen class in random order, starting with the open image if it still needs subclassifying. Images you have completed are skipped, so you can stop and pick up later." +
                         "\n\nPress I during the session to show or hide the controls." +
                         "\nGreyed out until everything above is filled in: hover over it to see what's missing.",
                  now  : {
                      List missing = [ users.getValue() ? null : "a ${modeWord()}".toString(),
                                       annoClassField.getValue() ? null : "an annotation class",
                                       classifyingNameSelection.getEditor().getText() ? null : "a subclassification name",
                                       gridSizeField.getText() || gridSizeField.isDisabled() ? null : "a grid size",
                                       channelCheckboxes.any { it.isSelected() } ? null : "a channel" ].findAll()
                      missing ? "Not ready. Still needs ${listOut( missing )}." :
                                "Ready to ${classifierModeBtn.isSelected() ? 'subclassify' : 'review'} '${annoClassField.getValue().name}' annotations as ${users.getValue()}."
                  } ]
            ] )

        // Making sure to bring the selection back to the ok button after re-enable first time
        long scriptStart = System.currentTimeMillis()
        boolean firstPass = true
        
        okBtn.disableProperty().addListener { obs, wasDisabled, isNowDisabled ->
            
            boolean withinStartupWindow = (System.currentTimeMillis() - scriptStart) < 100
            
            if (!isNowDisabled && firstPass && withinStartupWindow) {
                okBtn.requestFocus()
                firstPass = false
            }
        }
        
        
        // -- Waiting for output and passing preferences to rest of code --
        // The "?" button goes at the far left, ahead of Export and Import: the button row lays its buttons out
        // by kind, with the left-hand kind (L) first, so the help kind (E) is moved in front of it
        def buttonRow = preferencesDialog.getDialogPane().lookup( ".button-bar" )
        if (buttonRow instanceof ButtonBar) { buttonRow.setButtonOrder( "E" + buttonRow.getButtonOrder().replace( "E", "" ) ) }
        
        def dlgResult = preferencesDialog.showAndWait()        
        return dlgResult.get() == ButtonType.OK ? collectCurrentPrefs() : null
    }
}


// === GETTING AND CHECKING USER PREFERENCES ===
Map codePreferences

boolean firstPrompt = true

preferencesLoop:
while (true) {
    // Collect user preferences
    codePreferences = getCodePreferences( session, firstPrompt )
    firstPrompt = false
    
    // Writing full project matrix + image matrix to external file to "remember" prefs in project
    if (codePreferences != null) {    
        writePreferencesMaptoJSON( session.prefsFile, codePreferences )
        
    } else return // If cancelling
    
    
    // --- Storing selected preferences for use in script ---
    session.userName            = codePreferences.userName
    session.classifiers         = codePreferences.classifiers
    session.reviewers           = codePreferences.reviewers
    session.classifyingName     = codePreferences.classifyingName // Name of disease/marker being classified (for metadata)
    String modeKey              = session.classifierMode ? ActionStrings.CLASSIFIER_MODE : ActionStrings.REVIEWER_MODE
    session.userKey             = generateUserKey( session, modeKey, session.userName )
    session.classifierKeys      = session.classifiers.collect { generateUserKey( session, ActionStrings.CLASSIFIER_MODE, it ) } 
    session.checkingWork        = codePreferences.checkingWork // Set if the user is going back over their completed classifications
    session.toggleChannels      = codePreferences.selectedChannels // Channels to toggle for analysis (keep number of channels low)
    session.annoClass           = codePreferences.annoClass // Class of annotations to review
    session.gridSize            = codePreferences.gridSize // µm bins size for group review (will automatically add grid)
    session.singleObjects       = session.classifierMode && codePreferences.singleObjects == true // One annotation at a time, not a grid square of them
    session.statesAndColors     = codePreferences.statesAndColors // Map with the order of classes (to change) and respective colors
    session.reviewStateFreqs    = codePreferences.reviewStateFreqs // Frequencies of states to review
    session.reviewStates        = codePreferences.reviewStates // Which states to review
    session.instructions        = codePreferences.instructions // Whether instructions should be (or start) open/closed
    
    // --- Defining other needed variables ---
    session.freeViewModes     = [ActionStrings.ZOOM, ActionStrings.BRIGHTNESS]
    session.freeView          = false
    session.freeViewMode      = session.freeViewModes[0]
    session.reviewerStates    = session.statesAndColors.findAll { it.revState }.state
    session.prevSelectedAnnos = []
    session.lastMessage       = ""
    session.lastMessageTime   = 0
    
    Dialogs.showInfoNotification( "", (session.classifierMode ? "Classifier:" : "Reviewer:") + " ${session.userName}" )
    
    
    // --- Generating image list for auto switching ---
    def random = new Random()
    List imageList = project.getImageList()
    List randomImages = imageList.shuffled( random )
    session.currentEntry = currentImageData ? project.getEntry( currentImageData ) : null
    List imageOrder = session.checkingWork ? imageList : randomImages // If classifying: random order of images. If checking work: normal order to not lose place.
    
    
    // --- Collecting reviewer criteria ---
    session.reviewerCriteria = [all: [], any: [], different: false]
    session.reviewStateFreqs.eachWithIndex { freq, i ->
        switch (freq) {
            case ("all")      : session.reviewerCriteria.all << session.reviewStates[i]; break
            case ("any")      : session.reviewerCriteria.any  << session.reviewStates[i]; break
            case ("different"): session.reviewerCriteria.different = true               ; break
        }
    }
    
    
    // --- Checking image viability ---
    def problemImageLists = []
    
    def notFullyClassifiedImages = []
    problemImageLists << notFullyClassifiedImages
    def fullyClassifiedImages = []
    problemImageLists << fullyClassifiedImages
    def userIncompleteImages = []
    problemImageLists << userIncompleteImages
    def userCompleteImages = []
    problemImageLists << userCompleteImages
    def imagesWithoutAnnos = []
    problemImageLists << imagesWithoutAnnos
    def imagesWithNoReviewableAnnos = []  
    problemImageLists << imagesWithNoReviewableAnnos
    def usersWithIncompleteClassifications = [] as LinkedHashSet
    def incompleteUsersByImage = [:].withDefault { [] as LinkedHashSet }
    
    // Parallel checks for speed
    def nThreads = Math.min( Runtime.getRuntime().availableProcessors() * 2, imageOrder.size() )
    def pool = Executors.newFixedThreadPool( nThreads )
    
    // The open image's LIVE data may hold unsaved objects (e.g. a freshly drawn, still-unclassified
    // annotation). readImageData() only sees what's on disk, so scan the current entry from its live
    // hierarchy — otherwise an image whose only matching object is unsaved is judged to have none and
    // gets dropped from the work list, blocking the run at the image checks.
    def liveCurrentData = getCurrentImageData()

    def futures = imageOrder.collect { image ->
        pool.submit({
            def imageData = (image == session.currentEntry) ? liveCurrentData : image.readImageData()
            def imageAnnos = imageData
                .getHierarchy()
                .getAnnotationObjects()
                .findAll { classLabelForObject( it ) == session.annoClass }
                
            if (imageAnnos.isEmpty()) {
                return new ImageCheckResult(image: image, imageName: image.getImageName(), hasNoAnnos: true,
                    anyMissingClassifierKeys: false, anyMissingUserKey: false, anyUserKey: false,
                    anyAnnosToCheck: false, missingUsers: [] as Set, incompleteUsersByImage: [:])
            }
            
            def missingUsers = [] as LinkedHashSet
            def incompleteByImage = [:].withDefault { [] as LinkedHashSet }
            def annosMissingClassifierKeys = []
            
            if (session.reviewerMode) {
                imageAnnos.each { anno ->
                    def missing = session.classifierKeys.findAll { !anno.getMetadata().containsKey( it ) }
                                                        .collect { getUserFromKey( it ) }
                    if (missing) {
                        annosMissingClassifierKeys << anno
                        missingUsers.addAll( missing )
                        incompleteByImage[ image.getImageName() ].addAll( missing )
                    }
                }
            }
            
            boolean anyMissingUserKey = imageAnnos.any { anno ->
                if (session.reviewerMode) {
                    matchesMdClassification(
                        anno,
                        session.reviewerCriteria.all,
                        session.reviewerCriteria.any,
                        session.reviewerCriteria.different,
                        session
                    ) && !containsUserKey( anno, session )
                } else {
                    !containsUserKey( anno, session )
                }
            }
            
            boolean anyUserKey = imageAnnos.any { containsUserKey( it, session ) }
            
            boolean anyAnnosToCheck = session.reviewerMode ?
                findAnnosByMdClassification( imageAnnos, session.reviewerCriteria.all, session.reviewerCriteria.any,
                                             session.reviewerCriteria.different, session ) : true
                
            return new ImageCheckResult(
                image: image, imageName: image.getImageName(), hasNoAnnos: false,
                anyMissingClassifierKeys: !annosMissingClassifierKeys.isEmpty(),
                anyMissingUserKey: anyMissingUserKey, anyUserKey: anyUserKey,
                anyAnnosToCheck: anyAnnosToCheck,
                missingUsers: missingUsers, incompleteUsersByImage: incompleteByImage
            )
        } as Callable)
    }
    pool.shutdown()
    
    // Serial merge — order preserved via futures list order
    futures.each { f ->
        def r = f.get()
        if (r.hasNoAnnos) { imagesWithoutAnnos << r.image; return }
        if (session.reviewerMode) {
            (r.anyMissingClassifierKeys ? notFullyClassifiedImages : fullyClassifiedImages) << r.image
        }
        usersWithIncompleteClassifications.addAll( r.missingUsers )
        r.incompleteUsersByImage.each { k, v -> incompleteUsersByImage[ k ].addAll( v ) }
        
        if (r.anyAnnosToCheck) {
            if (r.anyMissingUserKey) {
                userIncompleteImages << r.image
            } else {
                userCompleteImages << [
                    image     : r.image,
                    userInput : r.anyUserKey
                ]
            }
        } else {
            imagesWithNoReviewableAnnos << r.image
        }
    }
    
    // --- Collecting images to process ---
    session.imagesToProcess = session.checkingWork ? new ArrayList( userCompleteImages.image ) : new ArrayList( userIncompleteImages )
    
    // Warning if some images haven't been fully classified and allowing exit
    List warningList = []
    def addWarningCheck = { List criteria, String warning, String group, List imagesList = null ->
        if (criteria.every { it }) {
            warningList << [message: warning, group: group, images: imagesList]
        }
    }
    
    // Create a small list with readable reviewer criteria for dropdown
    List reviewerCriteriaList = ["Reviewer criteria:"] + session.reviewerCriteria.findResults { key, value ->
        if (value) {
            key != "different"
                ? "Annotations ${session.frequencies[ key ]} ${value}"
                : "Annotations ${session.frequencies[ key ]}"
        }
    }
    
    List generalCriteria = ["User: ${session.userName}", 
                            "Object class: ${session.annoClass}", 
                            "Subclassification name: ${session.classifyingName}"]
    
    // Ordering problem image lists to reflect or of images in project
    def orderByName = [:]
    imageList.eachWithIndex { entry, idx ->
        orderByName[ entry.getImageName() ] = idx
    }
    problemImageLists.each { sublist ->
        sublist.sort { entry ->
            if (entry instanceof Map) { entry = entry.image }
            orderByName[ entry.getImageName() ] ?: Integer.MAX_VALUE
        }
    }
    
    // - Creating specific checks + warnings -
    // User completion warnings
    addWarningCheck( [session.reviewerMode, session.checkingWork, userCompleteImages.isEmpty(), !imagesWithNoReviewableAnnos.isEmpty()],
        "No images with annos meeting reviewer criteria have been completed by user.",
        "User Completion", reviewerCriteriaList )
    addWarningCheck( [session.classifierMode, session.checkingWork, userCompleteImages.isEmpty()],
        "User has not completed any images and cannot check work.",
        "User Completion", generalCriteria )
    addWarningCheck( [session.checkingWork, (userIncompleteImages.size() !in [0, imageList.size()])],
        "${userIncompleteImages.size()} image(s) have not been completed by user and cannot be checked.",
        "User Completion", userIncompleteImages )
    addWarningCheck( [session.classifierMode, !session.checkingWork, userIncompleteImages.isEmpty()],
        "User has subclassified all viable images.",
        "User Completion", generalCriteria )
    addWarningCheck( [session.reviewerMode, !session.checkingWork, userIncompleteImages.isEmpty()],
        "User has reviewed all viable images.",
        "User Completion", reviewerCriteriaList )
    
    // Classification & Review warnings
    addWarningCheck( [session.reviewerMode, !session.checkingWork, (notFullyClassifiedImages.size() == imageList.size())],
        "No images have complete subclassifications to review from ${usersWithIncompleteClassifications}.",
        "Subclassification & Review", generalCriteria )
    addWarningCheck( [session.reviewerMode, !session.checkingWork, (notFullyClassifiedImages.size() !in [0, imageList.size()])],
        "${notFullyClassifiedImages.size()} image(s) have incomplete subclassifications to review from ${usersWithIncompleteClassifications}.",
        "Subclassification & Review", notFullyClassifiedImages )
    
    // Annotation issues warnings
    addWarningCheck( [!imagesWithoutAnnos.isEmpty()],
        "${imagesWithoutAnnos.size()} image(s) have no \"${session.annoClass}\" annotations.",
        "Annotation Issues", imagesWithoutAnnos )
    
    
    // Sending warning if criteria are met
    if (!warningList.isEmpty()) {
        
        def exitBtnType      = new ButtonType( "Exit", ButtonBar.ButtonData.CANCEL_CLOSE )
        def continueBtnType  = new ButtonType( "Continue", ButtonBar.ButtonData.OK_DONE )
        def checkWorkBtnType = new ButtonType( "Check completed annos", ButtonBar.ButtonData.OK_DONE )
        def backBtnType      = new ButtonType( "Back", ButtonData.BACK_PREVIOUS )
        
        def warningDecision = runOnFxThreadAndWait {
            
            def unfinishedAlert = new Alert( AlertType.CONFIRMATION )
            unfinishedAlert.setTitle( "WARNING: Issue(s) with images" )
            unfinishedAlert.getDialogPane().setPrefHeight( warningList.size() * 60 )
            
            if (session.imagesToProcess.isEmpty()) {
                if (userCompleteImages.userInput.contains( true )) {
                    session.imagesToProcess = new ArrayList( userCompleteImages.image )
                    session.checkingWork = true
                    unfinishedAlert.getButtonTypes().setAll( backBtnType, checkWorkBtnType, exitBtnType )
                    def checkWorkBtn = unfinishedAlert.getDialogPane().lookupButton( checkWorkBtnType )
                    checkWorkBtn.setDefaultButton( false )
                    ButtonBar.setButtonUniformSize( checkWorkBtn, false )      
                } else {
                    unfinishedAlert.getButtonTypes().setAll( backBtnType, exitBtnType )
                }
            } else {
                unfinishedAlert.getButtonTypes().setAll( backBtnType, exitBtnType, continueBtnType )
                unfinishedAlert.getDialogPane().lookupButton( continueBtnType ).setDefaultButton( false )
                unfinishedAlert.setContentText( "Continue anyway?" )
            }
            
            unfinishedAlert.getDialogPane().lookupButton( exitBtnType ).setDefaultButton( true )
            
            // Build grouped warning UI
            def warningLabelBox = new VBox( 8 )
            
            // Group warnings by their group label, preserving insertion order
            Map grouped = warningList.groupBy { it.group }
            
            grouped.each { groupName, items ->
                
                // Group header label
                def groupLabel = new Label( groupName )
                groupLabel.setStyle( "-fx-font-weight: bold; -fx-text-fill: #555555; -fx-padding: 10 0 2 10;" )
                warningLabelBox.getChildren().add( groupLabel )
                
                // Warning items within the group
                items.each { item ->
                    List problemImageList = item.images ?: []
                    def rowCount = Math.min( 3, Math.max( 1, problemImageList.size() ) )
                    
                    if (problemImageList.isEmpty()) {
                        def tp = new TitledPane( item.message, null )
                        tp.setExpanded( false )
                        tp.setCollapsible( false )
                        warningLabelBox.getChildren().add( tp )
                    } else {
                        def imageText = new TextArea( problemImageList.join( "\n" ) )
                        imageText.setEditable( false )
                        imageText.setWrapText( true )
                        imageText.setPrefRowCount( Math.max( 1, problemImageList.size() ) )
                        imageText.setMaxWidth( Double.MAX_VALUE )
                        
                        def scroll = new ScrollPane( imageText )
                        scroll.setFitToWidth( true )
                        scroll.setPrefViewportHeight( Math.min( 3, Math.max( 1, problemImageList.size() ) ) * 15 )
    
                        def tp = new TitledPane( item.message, scroll )
                        tp.setExpanded( false )
                        warningLabelBox.getChildren().add( tp )
                    }
                }
            }
    
            unfinishedAlert.getDialogPane().setHeader( warningLabelBox )
    
            return unfinishedAlert.showAndWait().orElse( exitBtnType )
        }
        
        switch (warningDecision) {
            case (continueBtnType)  : break preferencesLoop
            case (checkWorkBtnType) : break preferencesLoop
            case (backBtnType):       continue
            default:                  return
        }
    }
    
    // If no warnings or continued, moving into execution
    break preferencesLoop
}


// Setting current image first if needs classification
if (session.currentEntry in session.imagesToProcess) {
    session.imagesToProcess.remove( session.currentEntry )
    session.imagesToProcess.addFirst( session.currentEntry )
}


// Collect existing viewer settings to restore after script
session.viewer = getCurrentViewer()
session.view = session.viewer.getView()
viewerSettings.overlayOptions = session.viewer.getOverlayOptions()
viewerSettings.wasGridOn = viewerSettings.overlayOptions.getShowGrid()
viewerSettings.oGridX = PathPrefs.gridSpacingX.get()
viewerSettings.oGridY = PathPrefs.gridSpacingY.get()
viewerSettings.wasOverviewOn = ViewerPlusDisplayOptions.getSharedInstance().getShowOverview()
viewerSettings.oldAnnoThickness = PathPrefs.annotationStrokeThicknessProperty().get()
def oo = viewerSettings.overlayOptions
viewerSettings.oldOverlay = [showAnnotations: oo.getShowAnnotations(), fillAnnotations: oo.getFillAnnotations(),
                             showDetections : oo.getShowDetections(),  fillDetections : oo.getFillDetections(),
                             opacity        : oo.getOpacity()]


// =====================

// ===== EXECUTION =====
def queue = new LinkedBlockingQueue<String>()
// -- Loop to review image (can repeat on another image) --
imageLoop:
for (int imageIndex = 0; imageIndex < session.imagesToProcess.size(); imageIndex++) {
    
    def currentImage = session.imagesToProcess[ imageIndex ]
    def imageData = getNextImage( currentImage )

    // Changing viewer settings for run (each image, as finishing the last one restored the user's own)
    Platform.runLater {
        ViewerPlusDisplayOptions.getSharedInstance().setShowOverview( false )
        PathPrefs.annotationStrokeThicknessProperty().set( 1 )
        changeGrid( viewerSettings, !session.singleObjects, session.gridSize, session.gridSize ) // no grid when going one at a time
        viewerSettings.overlayOptions.setShowDetections( true )
        viewerSettings.overlayOptions.setFillDetections( true )
        viewerSettings.overlayOptions.setShowAnnotations( true )
        viewerSettings.overlayOptions.setFillAnnotations( false )
        viewerSettings.overlayOptions.setOpacity( 1 )
    }

    // Collect (new) image important data
    session.server = imageData.getServer()
    session.hierarchy = imageData.getHierarchy()
    
    // Get all desired class annotations
    session.allClassAnnos = session.hierarchy.getAnnotationObjects().findAll {
        classLabelForObject( it ) == session.annoClass
    }
    
    // Getting annotations to classify/review/check
    def annotations = session.allClassAnnos.clone()
    
    // Per-object colour/state memories; clear so the previous image's entries can't leak in
    session.currentAnnoColorsMap.clear()
    session.annoStateMap.clear()
    
    // Getting channel specifics for toggling and turning off others ---
    def availableChannels = session.viewer.getImageDisplay().availableChannels()
    session.controllableChannels = availableChannels.findAll { ch ->
        def chBaseName = baseChannelName( ch.getName() ) // Strip "(C1)"-style suffix to match stored base names
        session.toggleChannels.any { it.equalsIgnoreCase( chBaseName ) } // Exact match so e.g. "CD3" no longer matches "CD31"
    }
    def intitalDisplay = session.viewer.getImageDisplay()
    session.channelSpecifics = []
    session.controllableChannels.each { channel ->
        session.channelSpecifics << [
            channel: channel,
            color: channel.getColor(),
            name: channel.getOriginalChannelName(),
            state: channel in intitalDisplay.selectedChannels()
        ]
    }
    
    Platform.runLater {
        for (channel : availableChannels) {
            if (channel !in session.controllableChannels) {
                intitalDisplay.setChannelSelected( channel, false )
            }
        }
        session.viewer.repaintEntireImage()
    }
    
    // Storing selected channel(s) brightness info for when changing
    session.brightnessInfo = [:]
    Platform.runLater {
        for (channel : session.controllableChannels) {
            def channelDisplay = session.viewer.getImageDisplay()
            
            float minDisplay = channel.getMinDisplay()
            float maxDisplay = channel.getMaxDisplay()
            float maxAvailable = channel.getMaxAllowed()
            channelDisplay.autoSetDisplayRange( channel )
            float autoMinDisplay = channel.getMinDisplay()
            channelDisplay.setMinMaxDisplay( channel, minDisplay, maxDisplay )
            
            int nExpSteps = 50
            int nStdSteps = nExpSteps / 2
            float stdStepSize = maxAvailable / nStdSteps
            def expSteps = new float[ nExpSteps ]
            def stdSteps = new float[ nStdSteps ]
            for (i in 1..nExpSteps) {
               float expVal = (autoMinDisplay + 1) * Math.pow(maxAvailable / (autoMinDisplay + 1), i / nExpSteps)
               float stdVal = stdStepSize * i
               expSteps[i-1] = expVal
               if (stdVal <= maxAvailable) {
                   stdSteps[i-1] = stdVal
               }
            }
            
            session.brightnessInfo[ channel ] = [
                maxAvailable : maxAvailable,
                autoMinDisplay : autoMinDisplay,
                expSteps : expSteps,
                stdSteps : stdSteps
            ]
        }
    }
    
    // Changing positive annotation colors temporarily if desired
    def BLACK = ColorTools.makeRGB( 000, 000, 000 )
    
    
    if (session.classifierMode) {
        for (anno : annotations) {
            def value = anno.getMetadata().get( session.userKey )
            anno.setColor( session.statesAndColors.find { it.state == value }?.color ?: BLACK )
        }
    // Setting all annotations to black; the reviewer branch below recolors the ones being reviewed.
    // (Can't diff against "annotations" here - it isn't narrowed to the review set until later.)
    } else {
        for (anno : session.allClassAnnos) { anno.setColor( BLACK ) }
    }
    Platform.runLater { session.viewer.repaintEntireImage() }
    
    buildHighlightStatesAndColorsMap( session, session.classifierMode ? [session.userKey] : session.classifierKeys )
    
    // Turning off clicking and scrolling temporarily during the script to be brought back after
    addScrollBlocker( session, viewerSettings )   
    addHoverFilter( session, viewerSettings )
    
    // Establishing/storing important variables
    session.zoomed = false
    session.channelToChange = session.channelSpecifics.find { it.state }?.channel // Defaulting to first channel that's on
    def nonFreeViewerState
    boolean firstLoop = true
    
    
    Platform.runLater { resetSelection() }
    session.pixelSize = session.server.getPixelCalibration().getAveragedPixelSizeMicrons()
    
    
    def sortedBins = []
    
    int setBinIndex = 0
    int setAnnoIndex = 0
    
    currentImageLoop:
    while (true) {
        
        // Create and show the alert (control panel) once
        def alert = null
        runOnFxThreadAndWait {
            alert = new Alert( AlertType.NONE )
            alert.setX( 0 )
            alert.setY( 0 )
            alert.initModality( Modality.NONE )
        
            // Pass queue in so buttons put results instead of closing
            buildControlPanel( alert, session, uiElements, viewerSettings, queue )
            updateControlPanel( alert, session, uiElements, viewerSettings, queue )
        
            alert.show()
            
            def window = alert.getDialogPane().getScene().getWindow()
            window.setOnCloseRequest { queue.put( ActionStrings.CANCEL ) }
            window.setAlwaysOnTop( true )
            window.focusedProperty().addListener { obs, wasFocused, isFocused ->
                if (!isFocused) { window.requestFocus() }
            }
            
            // Get and stash the right edge of alert for reference
            session.alertRightEdge = window.getX() + window.getWidth()
        }
        
        
        
        // --- CLASSIFIER MODE: BIN CLASSIFICATIONS ---
        if (session.classifierMode) {
            
            // Bin setup
            def binMap = [:].withDefault { [] }
            for (anno : annotations) {
                double cX = anno.getROI().getCentroidX()
                double cY = anno.getROI().getCentroidY()
                int binX = cX * session.pixelSize / session.gridSize
                int binY = cY * session.pixelSize / session.gridSize
                def binKey = [binX, binY]
                binMap[ binKey ] << anno
            }
            sortedBins = binMap.keySet().sort { a, b -> a[1] <=> b[1] ?: a[0] <=> b[0] }
            
            // One at a time: every annotation becomes a group of its own. They keep the grid's order (square
            // by square, then top to bottom and left to right inside a square), so the view moves across the
            // image steadily instead of jumping about. Everything below then works on groups of one
            if (session.singleObjects) {
                def singles = [:]
                sortedBins.each { key ->
                    binMap[ key ].sort( false ) { a, b ->
                        a.getROI().getCentroidY() <=> b.getROI().getCentroidY() ?: a.getROI().getCentroidX() <=> b.getROI().getCentroidX()
                    }.eachWithIndex { anno, k -> singles[ [key[0], key[1], k] ] = [anno] }
                }
                binMap = singles
                sortedBins = singles.keySet() as List
            }
            
            // Storing classification data
            for (anno : annotations) {
                def value = anno.getMetadata().get( session.userKey )
                value ? session.annoStateMap.put( anno, value ) : session.annoStateMap.put( anno, session.statesAndColors[0].state )
            }
            
            // Group review (only review)
            for (int binIndex = setBinIndex; binIndex < sortedBins.size(); binIndex++) {
                
                def binKey = sortedBins[ binIndex ]
                def group = binMap[ binKey ]
                session.hoverAnnos = group
                session.currentGroup = group
                
                // Checking if annotations need classification (skipping if they do not)
                session.groupDone = group.every { containsUserKey( it, session ) }
                if (!session.checkingWork && firstLoop && session.groupDone) continue
                
                // If wanted, classifed annos out of view will be filled (using detections)
                def detObjs = session.hierarchy.getDetectionObjects()
                def annoDetMap = detObjs.findAll { it.getMetadata()?.get( "annoID" ) }
                .collectEntries { det ->
                    [ (det.getMetadata().get( "annoID" )) : det ]
                }
                def detsToAdd = []
                def detsToRemove = []
                
                // Changing color of already classified annos
                for (anno : annotations) {
                    def mdValue = anno.getMetadata().get( session.userKey )
                    def id = anno.getID().toString()
                    
                    if (mdValue)            { anno.setColor( session.statesAndColors.find { it.state == mdValue }?.color ?: BLACK ) }
                    else if (anno in group) { anno.setColor( session.statesAndColors[0].color ) }
                    else                    { anno.setColor( BLACK ) }
                    
                    // If wanted, classifed annos out of view will be filled (using detections)
                    def existingDet = annoDetMap[ id ]
                    if (mdValue && (anno !in group)) {
                        int detColor = session.statesAndColors.find { it.state == mdValue }?.color ?: BLACK
                        // Detections are painted from a cached overlay, so a filler whose classification
                        // changed (e.g. after "Back") is swapped out rather than recoloured in place -
                        // recolouring alone fires no hierarchy event and leaves the stale colour on screen.
                        if (!existingDet || existingDet.getColor() != detColor) {
                            def detObj = PathObjects.createDetectionObject( anno.getROI() )
                            detObj.getMetadata().put( "annoID", id )
                            detObj.setColor( detColor )
                            detsToAdd << detObj
                            if (existingDet) { detsToRemove << existingDet }
                        }
                    } else if ((anno in group) && existingDet) {
                        detsToRemove << existingDet
                    }
                }
                
                session.hierarchy.removeObjects( detsToRemove, true )
                session.hierarchy.insertPathObjects( detsToAdd )
                Platform.runLater { session.viewer.repaintEntireImage() }
                
                // Calcualating bin coordinates for viewer centering
                double binX = (session.gridSize / session.pixelSize) * (binKey[0] + 0.5)
                double binY = (session.gridSize / session.pixelSize) * (binKey[1] + 0.5)            
                double adjustedX = adjustX( session, session.gridSize, session.gridSize, binX )
                
                // Focus viewer on group, or on the one annotation (with as much again around it, as in reviewer mode)
                if (session.singleObjects) {
                    def roi = group[ 0 ].getROI()
                    double roiW = roi.getBoundsWidth() * session.pixelSize
                    double roiH = roi.getBoundsHeight() * session.pixelSize
                    double roiX = adjustX( session, roiW * 2, roiH * 2, roi.getCentroidX() )
                    changeViewer( roiX, roi.getCentroidY(), roiW * 2, roiH * 2, true, session, null ) {
                        nonFreeViewerState = saveViewerState(session)
                    }
                } else {
                    changeViewer( adjustedX, binY, session.gridSize, session.gridSize, true, session, null ) {
                        nonFreeViewerState = saveViewerState(session)
                    }
                }
                
                // Remember current viewer state for changes
                nonFreeViewerState = saveViewerState( session )
                
                // --- Loop if adjusting settings without moving to next group ---
                annoGroupLoop:
                while (true) { 
                    
                    session.groupDone = group.every { containsUserKey( it, session ) }
                    
                    // Updating control panel
                    Platform.runLater {
                        alert.setTitle(!session.freeView ?
                            "Classify ${session.singleObjects ? 'anno' : 'group'} ${binIndex + 1}/${sortedBins.size()} — ${session.userName}" :
                            "Free View")
                            
                            updateControlPanel( alert, session, uiElements, viewerSettings, queue )
                    }
                    
                    session.UI_COMMAND = queue.take()
                    
                    
                    // -- Checking/acting on user response --
                    switch (session.UI_COMMAND) {
                        
                        case (ActionStrings.CANCEL):
                            runOnFxThreadAndWait { alert.getDialogPane()?.getScene()?.getWindow()?.hide() }
                            restoreToPrescript( session, viewerSettings )
                            codePreferences.instructions = session.instructions
                            writePreferencesMaptoJSON( session.prefsFile, codePreferences )
                            return
                        
                        case (ActionStrings.INSTRUCTIONS):
                            session.instructions = !session.instructions
                            break
                        
                        case (ActionStrings.BACK):
                            if (binIndex > 0) {
                                binIndex -= 2
                                break annoGroupLoop
                            } else {
                                session.message = session.singleObjects ? "Already at first annotation." : "Already at first group."
                                conditionallyFireMessage( session )
                            }
                            break
                            
                        case (ActionStrings.TOGGLE_FOCUS): // Looking for annotation mouse is over
                            if (!session.singleObjects || session.freeView) { toggleFocus( session ) } // one at a time, only free view has it
                            break
                            
                        case (ActionStrings.TOGGLE_CLASS):
                            // One at a time, there is only one annotation it could mean, so the pointer needn't be on it
                            def toChange = session.hoveredAnno ?: (session.singleObjects && !session.freeView ? group[ 0 ] : null)
                            if (toChange) {
                                changeClassification( toChange, session )
                            }
                            break
                            
                        case (ActionStrings.TOGGLE_ANNOS): // Toggling annotations on or off
                            getAnnosVisibilityStatus( viewerSettings ) ? 
                                session.prevSelectedAnnos = getSelectedObjects().collect { it } : 
                                Platform.runLater { selectObjects( session.prevSelectedAnnos ) }
                            toggleAnnos( viewerSettings )
                            break
                       
                        case (ActionStrings.TOGGLE_GRID): // Toggles grid on or off
                            boolean gridOn = viewerSettings.overlayOptions.getShowGrid()
                            changeGrid( viewerSettings, !gridOn )
                            break
                        
                        case (ActionStrings.TOGGLE_CHANNEL): // Toggles channel of interest or remembers for changing brightness (if turned off, finds first one that's on)
                            def channelToToggle = session.channelSpecifics.find { it.name == session.toggleChannel }?.channel
                            toggleChannel( channelToToggle, session )
                            boolean toggledChannelState = session.channelSpecifics.find { it.channel == channelToToggle }?.state
                            boolean brightnessChannelState = session.channelSpecifics.find { it.channel == session.channelToChange }?.state
                            def backupChannel = session.channelSpecifics.find { it.state }?.channel
                            
                            if (toggledChannelState) {
                                session.channelToChange = channelToToggle
                            } else {
                                session.channelToChange = brightnessChannelState ? session.channelToChange : backupChannel
                            }
                            break
                        
                        case (ActionStrings.BRIGHT_UP):
                            changeBrightness( "UP", session )
                            break
                        
                        case (ActionStrings.BRIGHT_DOWN):
                            changeBrightness( "DOWN", session )
                            break
                        
                        case (ActionStrings.REMOVE):
                            for (anno in group) {
                                anno.getMetadata().remove( session.userKey )
                                session.annoStateMap.put( anno, session.statesAndColors[0].state )
                                anno.setColor ( session.statesAndColors[0].color )
                            }
                            Platform.runLater { session.viewer.repaintEntireImage() }
                            
                            buildHighlightStatesAndColorsMap( session, [session.userKey] )
                            break
                        
                        case (ActionStrings.TOGGLE_FREE_VIEW):
                            session.freeView = !session.freeView
                            
                            Platform.runLater {
                                if (session.freeView) {
                                    buildHighlightStatesAndColorsMap( session, [session.userKey] )
                                    
                                    // Storing existing colors for anno before any change
                                    session.allClassAnnos.each { session.currentAnnoColorsMap[ it ] = it.getColor() }
                                    
                                    // Defining which annos can be focused on
                                    session.hoverAnnos = session.allClassAnnos
                                    
                                    // Getting/generating/setting colors of different anno classifications
                                    colorAnnosFromMap( session, [session.userKey] )
                                    
                                    viewerSettings.overlayOptions.setShowDetections( false )
                                    viewerSettings.overlayOptions.setShowAnnotations( true )
                                        
                                    session.prevSelectedAnnos = getSelectedObjects().collect { it }
                                    session.hierarchy.getSelectionModel().clearSelection()
                                    
                                    double sWidth = session.server.getWidth()
                                    double sHeight = session.server.getHeight()
                                    changeViewer( sWidth/2, sHeight/2, sWidth/1.4, sHeight/1.4, false, session )
                                    
                                    removeScrollBlocker( session, viewerSettings )
                                    
                                } else {
                                    addScrollBlocker( session, viewerSettings )
                                    
                                    // Defining which annos can be focused on
                                    session.hoverAnnos = group
                                    
                                    restoreViewerState( nonFreeViewerState, session )
                                    
                                    viewerSettings.overlayOptions.setShowDetections( true )
                                    viewerSettings.overlayOptions.setShowAnnotations( true )
                                        
                                    changeGrid( viewerSettings, !session.singleObjects )
                                    
                                    session.freeViewMode = session.freeViewModes[0]
                                    session.allClassAnnos.each { it.setColor( session.currentAnnoColorsMap[ it ] ?: BLACK ) }
                                    
                                    selectObjects( session.prevSelectedAnnos )
                                    
                                    session.zoomed = false
                                }
                                
                                session.viewer.repaintEntireImage()
                            }
                            break
                            
                        case (ActionStrings.SWITCH_FREE_VIEW_MODE):
                            switchFVmode( session, viewerSettings )
                            break
                        
                        case (ActionStrings.NEXT): // Updating classifications after each group
                            for (anno : group) {
                                def state = session.annoStateMap.get( anno )
                                anno.getMetadata().put( session.userKey, state )
                            }
                            
                            break annoGroupLoop
                    }
                    firstLoop = false
                }
                firstLoop = false
            }
        
        
        // --- FOR REVIEWER MODE: ANNOTATIONS INDIVIDUALLY ---
        } else if (session.reviewerMode) {
            
            annotations = findAnnosByMdClassification( session.allClassAnnos, session.reviewerCriteria.all,
                                                       session.reviewerCriteria.any, session.reviewerCriteria.different, session )
            
            // Changing color of already classified annos.
            // Every anno is touched: anything outside the review criteria (or with no reviewer state
            // yet) goes black, otherwise it keeps the colour it had before the script ran.
            def reviewableSet = annotations as Set
            for (anno in session.allClassAnnos) {
                def value = anno.getMetadata().get( session.userKey )
                def color = (anno in reviewableSet) && (value in session.reviewerStates) ?
                    session.statesAndColors.find { it.state == value }?.color : null
                anno.setColor( color ?: BLACK )
            }
            Platform.runLater { session.viewer.repaintEntireImage() }
            
            // Individual annotation review
            for (int annoIndex = setAnnoIndex; annoIndex < annotations.size(); annoIndex++) {
                
                def annotation = annotations[ annoIndex ]
                
                // Defining which annos can be focused on (when applicable)
                session.hoverAnnos = session.allClassAnnos
                
                // Skipping to annotations that needs review if others alrady have been
                // (when checking work the already-reviewed annos are exactly the ones we want)
                if (!session.checkingWork && firstLoop && containsUserKey( annotation, session )) continue
                
                Platform.runLater { session.viewer.repaintEntireImage() }
                
                // Focusing on specific annotation
                def roi = annotation.getROI()       
                double adjustedX = adjustX( session, roi.getBoundsWidth(), roi.getBoundsHeight(), roi.getCentroidX() )         
                changeViewer( adjustedX, roi.getCentroidY(), roi.getBoundsWidth() * 2, roi.getBoundsHeight() * 2, false, session )
                Platform.runLater { selectObjects( annotation ) }
                
                // --- Loop if adjusting settings without moving to next group ---
                annoLoop:
                while (true) {
                    
                    // Updating control panel
                    Platform.runLater {
                        alert.setTitle( !session.freeView ? 
                            "Review anno ${annoIndex + 1}/${annotations.size()} — ${session.userName}" :
                            "Free View" )
                    
                        updateControlPanel( alert, session, uiElements, viewerSettings, queue )
                    }
                    
                    session.UI_COMMAND = queue.take()
                    
                    // -- Checking/acting on user response --
                    switch (session.UI_COMMAND) {
                        
                        case (ActionStrings.CANCEL): // If cancelled, puts back previous settings
                            runOnFxThreadAndWait { alert.getDialogPane()?.getScene()?.getWindow()?.hide() }
                            restoreToPrescript( session, viewerSettings )
                            codePreferences.instructions = session.instructions
                            writePreferencesMaptoJSON( session.prefsFile, codePreferences )
                            return
                        
                        case (ActionStrings.INSTRUCTIONS):
                            session.instructions = !session.instructions
                            break
                        
                        case (ActionStrings.BACK):
                            if (annoIndex > 0) {
                                annoIndex -= 2
                                break annoLoop
                            } else {
                                session.message = "Already at first annotation."
                                conditionallyFireMessage( session )
                                break
                            }
                        case (ActionStrings.TOGGLE_ANNOS): // Toggling annotations on or off
                            getAnnosVisibilityStatus( viewerSettings ) ? 
                                session.prevSelectedAnnos = getSelectedObjects().collect { it } : 
                                Platform.runLater { selectObjects( session.prevSelectedAnnos ) }
                            toggleAnnos( viewerSettings )
                            break
                        
                        case (ActionStrings.TOGGLE_GRID): // Toggles grid on or off
                            boolean gridOn = viewerSettings.overlayOptions.getShowGrid()
                            changeGrid( viewerSettings, !gridOn )
                            break
                        
                        case (ActionStrings.TOGGLE_CHANNEL): // Toggles channel of interest or remembers for changing brightness (if turned off, finds first one that's on)
                            def channelToToggle = session.channelSpecifics.find { it.name == session.toggleChannel }?.channel
                            toggleChannel( channelToToggle, session )
                            boolean toggledChannelState = session.channelSpecifics.find { it.channel == channelToToggle }?.state
                            boolean brightnessChannelState = session.channelSpecifics.find { it.channel == session.channelToChange }?.state
                            def backupChannel = session.channelSpecifics.find { it.state }?.channel
                            
                            if (toggledChannelState) {
                                session.channelToChange = channelToToggle
                            } else {
                                session.channelToChange = brightnessChannelState ? session.channelToChange : backupChannel
                            }
                            break
                            
                        case (ActionStrings.BRIGHT_UP):
                            changeBrightness( "UP", session )
                            break
                            
                        case (ActionStrings.BRIGHT_DOWN):
                            changeBrightness( "DOWN", session )
                            break
                            
                        case (ActionStrings.TOGGLE_FREE_VIEW):
                            session.freeView = !session.freeView
                            
                            Platform.runLater {
                                if (session.freeView) {
                                    nonFreeViewerState = saveViewerState( session )
                                    
                                    // Storing existing colors for anno before any change
                                    session.allClassAnnos.each { session.currentAnnoColorsMap[ it ] = it.getColor() }
                                    
                                    // Getting/generating/setting colors of different anno classifications
                                    colorAnnosFromMap( session, session.classifierKeys )
                                    
                                    session.prevSelectedAnnos = getSelectedObjects().collect { it }
                                    session.hierarchy.getSelectionModel().clearSelection()
                                    
                                    double sWidth = session.server.getWidth()
                                    double sHeight = session.server.getHeight()
                                    changeViewer( sWidth/2, sHeight/2, sWidth/1.4, sHeight/1.4, false, session )
                                    
                                    viewerSettings.overlayOptions.setShowAnnotations( true )
                                    
                                    removeScrollBlocker( session, viewerSettings )
                                    
                                } else {
                                    addScrollBlocker( session, viewerSettings )
                                    
                                    restoreViewerState( nonFreeViewerState, session )
                                    session.freeViewMode = session.freeViewModes[0]
                                    session.allClassAnnos.each { it.setColor( session.currentAnnoColorsMap[ it ] ?: BLACK ) }
                                    selectObjects( session.prevSelectedAnnos )
                                    changeGrid( viewerSettings, true )
                                    
                                    viewerSettings.overlayOptions.setShowAnnotations( true )
                                }
                                
                                session.viewer.repaintEntireImage()
                            }
                            break
                        
                        // Switch scroll mode in free view between zoom and brightness
                        case (ActionStrings.SWITCH_FREE_VIEW_MODE):
                            switchFVmode( session, viewerSettings )
                            break
                        
                        // Zoom in on specific annotation
                        case (ActionStrings.TOGGLE_FOCUS):
                            toggleFocus( session )                 
                            break
                        
                        case (ActionStrings.SET_CLASS ):
                            def md = annotation.getMetadata()
                            String setClass = session.reviewerClassification
                            md.put( session.userKey, setClass )
                            annotation.setColor( session.statesAndColors.find { it.state == setClass }?.color ?: BLACK )
                            break annoLoop
                    }
                    firstLoop = false
                }
                firstLoop = false
            }
        }
        
        runOnFxThreadAndWait { alert.getDialogPane()?.getScene()?.getWindow()?.hide() }
        
        fireHierarchyUpdate()
        
        
        // --- Final message and prompt for next image ---
        def moveToNextImage = runOnFxThreadAndWait {
            int imagesDone = imageIndex + 1
            int imagesToDo = session.imagesToProcess.size() - imagesDone
            alert = new Alert( Alert.AlertType.NONE )
            alert.setTitle( "Image complete! (${imagesToDo} images left)" )
            alert.setHeaderText( null )
            alert.setContentText( "Save subclassifications and move to next image?" )
            
            // Customize buttons to based on if user inupt
            boolean anyFinishedImageAnnos = session.allClassAnnos.any { anno ->
                return containsUserKey( anno, session )
            }
            if (annotations.isEmpty() && !anyFinishedImageAnnos) {
                alert.getButtonTypes().setAll( ButtonType.YES, ButtonType.NO )
            } else {
                alert.getButtonTypes().setAll(
                    ButtonType.YES,
                    ButtonType.NO,
                    new ButtonType( ActionStrings.BACK_TO_LAST, ButtonData.BACK_PREVIOUS ),
                    new ButtonType( ActionStrings.REVIEW_ALL, ButtonData.HELP_2 )
                )
            }
            
            def result = alert.showAndWait()
            return result.get().getText()
        }
        
        // If next image wanted, saving changes and changing image
        switch (moveToNextImage) {
            case (ActionStrings.YES):
                restoreToPrescript( session, viewerSettings )
                codePreferences.instructions = session.instructions
                writePreferencesMaptoJSON( session.prefsFile, codePreferences )
                
                def entry = project.getEntry( imageData )
                entry.saveImageData( imageData )
                project.syncChanges()
                
                break currentImageLoop
            
            // Stopping script and resetting colors if not
            case (ActionStrings.NO):
                restoreToPrescript( session, viewerSettings )
                codePreferences.instructions = session.instructions
                writePreferencesMaptoJSON( session.prefsFile, codePreferences )
                return
            
            // Back to last group or anno if done
            case (ActionStrings.BACK_TO_LAST):
                setBinIndex = sortedBins.size() - 1
                setAnnoIndex = annotations.size() - 1
                firstLoop = false
                break
            
            // Back to first group or anno if done
            case (ActionStrings.REVIEW_ALL):
                setBinIndex = 0
                setAnnoIndex = 0
                firstLoop = false
                break
        }
    }
}

// Finished with project message
runOnFxThreadAndWait {
    def doneAlert = new Alert( AlertType.INFORMATION )
    doneAlert.setTitle( "Finished" )
    doneAlert.setHeaderText( "Project complete!" )
    doneAlert.setContentText( null )
    doneAlert.show()
}


// --- Importing packages ---
import groovy.transform.Field

import java.awt.MouseInfo
import java.awt.Color as AwtColor
import java.nio.file.Files
import java.util.Collections
import javafx.util.StringConverter
import java.util.concurrent.*
import java.util.concurrent.atomic.*

import javafx.animation.AnimationTimer
import javafx.animation.PauseTransition
import javafx.animation.FadeTransition
import javafx.util.Duration
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.beans.property.SimpleBooleanProperty
import javafx.collections.FXCollections
import javafx.event.*
import javafx.geometry.*
import javafx.scene.*
import javafx.scene.control.*
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.paint.Color
import javafx.scene.input.*
import javafx.scene.layout.*
import javafx.scene.text.*
import javafx.scene.shape.*
import javafx.stage.*

import qupath.lib.common.ColorTools
import qupath.lib.gui.tools.IconFactory
import qupath.lib.gui.viewer.QuPathViewer
import qupath.lib.gui.viewer.QuPathViewerListener
import qupath.lib.gui.viewer.ViewerPlusDisplayOptions

import javafx.beans.binding.Bindings

import com.google.gson.GsonBuilder
import com.google.gson.Gson

import org.controlsfx.glyphfont.FontAwesome


// Comments for tool launcher icon and tooltip
// ORDER: 4
// ICON: PENCIL_SQUARE_ALT
// Manually classify annotations