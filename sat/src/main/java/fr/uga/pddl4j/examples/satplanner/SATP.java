package fr.uga.pddl4j.examples.satplanner;

import fr.uga.pddl4j.parser.DefaultParsedProblem;
import fr.uga.pddl4j.parser.RequireKey;
import fr.uga.pddl4j.plan.Plan;
import fr.uga.pddl4j.plan.SequentialPlan;
import fr.uga.pddl4j.planners.AbstractPlanner;
import fr.uga.pddl4j.problem.DefaultProblem;
import fr.uga.pddl4j.problem.Fluent;
import fr.uga.pddl4j.problem.Problem;
import fr.uga.pddl4j.problem.operator.Action;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sat4j.core.Vec;
import org.sat4j.core.VecInt;
import org.sat4j.minisat.SolverFactory;
import org.sat4j.specs.*;
import picocli.CommandLine;

/**
 * A SAT-based planner that solves planning problems by converting them to SAT problems.
 * This planner uses a SAT solver to find solutions and then converts them back to plans.
 */
@CommandLine.Command(name = "SATP", version = "SATP 1.0", description = "Solves a specified planning problem using a SAT solver.", 
    sortOptions = false, mixinStandardHelpOptions = true, headerHeading = "Usage:%n", 
    synopsisHeading = "%n", descriptionHeading = "%nDescription:%n%n", 
    parameterListHeading = "%nParameters:%n", optionListHeading = "%nOptions:%n")
public class SATP extends AbstractPlanner {
    private static final Logger LOGGER = LogManager.getLogger(SATP.class.getName());
    
    // The current maximum plan length tried
    private int currentPlanLength = 1;

    
    public static void main(String[] args) {
        try {
            final SATP plannerInstance = new SATP();
            CommandLine commandParser = new CommandLine(plannerInstance);
            commandParser.execute(args);
        } catch (IllegalArgumentException e) {
            LOGGER.fatal(e.getMessage());
        }
    }

    
    @Override
    public Problem instantiate(DefaultParsedProblem parsedProblem) {
        LOGGER.info("Instantiate ADL problem");
        final Problem problemInstance = new DefaultProblem(parsedProblem);
        problemInstance.instantiate();
        return problemInstance;
    }

    /**
     * Solves the SAT problem using the given clauses.
     * @param clauseCollection The collection of clauses representing the problem
     * @param planningProblem The original planning problem
     * @return The solution model if found, null otherwise
     */
    private int[] findSolution(Vec<IVecInt> clauseCollection, Problem planningProblem) 
            throws org.sat4j.specs.TimeoutException {
        // Calculate maximum number of variables needed
        final int maxVariables = (planningProblem.getFluents().size() + planningProblem.getActions().size()) 
                * this.currentPlanLength + planningProblem.getFluents().size();

        LOGGER.debug("Number clauses: {}\n", clauseCollection.size());

        // Initialize SAT solver
        ISolver satSolver = SolverFactory.newDefault();
        satSolver.newVar(maxVariables);
        satSolver.setExpectedNumberOfClauses(clauseCollection.size());

        try {
            // Add all clauses to the solver
            satSolver.addAllClauses(clauseCollection);
        } catch (ContradictionException e) {
            return null;
        }

        IProblem satProblem = satSolver;
        try {
            if (satProblem.isSatisfiable()) {
                LOGGER.info("Solution found!\n");
                return satProblem.model();
            } else {
                LOGGER.error("No solution exists\n");
                return null;
            }
        } catch (TimeoutException e) {
            LOGGER.error("Timeout occurred\n");
            throw new TimeoutException("Timeout while searching for solution");
        }
    }

    /**
     * Identifies the action corresponding to a given identifier.
     * @param planningProblem The planning problem
     * @param actionIdentifier The numeric identifier of the action
     * @return The corresponding Action object, or null if not found
     */
    private Action identifyAction(Problem planningProblem, int actionIdentifier) {
        if (actionIdentifier <= 0) {
            return null;
        }
        // Calculate index in the combined list of fluents and actions
        int index = (actionIdentifier - 1) % (planningProblem.getFluents().size() 
                + planningProblem.getActions().size());
        if (index >= planningProblem.getFluents().size()) {
            // Return the corresponding action if the index is in the action range
            return planningProblem.getActions().get(index - planningProblem.getFluents().size());
        }
        return null;
    }

    /**
     * Constructs a plan from the SAT solution model.
     * @param solutionModel The solution model from the SAT solver
     * @param planningProblem The planning problem
     * @return The plan
     */
    private Plan buildPlan(int[] solutionModel, Problem planningProblem) {
        Plan generatedPlan = new SequentialPlan();
        int actionPosition = 0;
        for (Integer identifier : solutionModel) {
            Action currentAction = identifyAction(planningProblem, identifier);
            if (currentAction != null) {
                generatedPlan.add(actionPosition, currentAction);
                actionPosition++;
            }
        }
        return generatedPlan;
    }

    
    @Override
    public Plan solve(final Problem planningProblem) {
        int[] solutionModel;
        
        // Incrementally try longer plan lengths until a solution is found
        while (true) {
            LOGGER.info("Attempting to find solution with plan length: {}", this.currentPlanLength);

            // Phase 1: Problem encoding to SAT
            final long encodingStart = System.currentTimeMillis();
            SATEncoder problemEncoder = new SATEncoder(planningProblem, this.currentPlanLength);
            Vec<IVecInt> encodedClauses = problemEncoder.convertToCNF();
            final long encodingEnd = System.currentTimeMillis();
            this.getStatistics().setTimeToEncode(
                this.getStatistics().getTimeToEncode() + (encodingEnd - encodingStart));

            // Phase 2: SAT solving
            final long solvingStart = System.currentTimeMillis();
            LOGGER.info("Starting SAT solver...\n");
            try {
                solutionModel = findSolution(encodedClauses, planningProblem);
            } catch (org.sat4j.specs.TimeoutException e) {
                final long solvingEnd = System.currentTimeMillis();
                this.getStatistics().setTimeToSearch(
                    this.getStatistics().getTimeToSearch() + solvingEnd - solvingStart);
                return null;
            }

            final long solvingEnd = System.currentTimeMillis();
            this.getStatistics().setTimeToSearch(
                this.getStatistics().getTimeToSearch() + solvingEnd - solvingStart);

            if (solutionModel == null) {
                // Double plan length and try again if no solution found
                this.currentPlanLength *= 2;
                LOGGER.info("No solution found, increasing plan length to {}", this.currentPlanLength);
            } else {
                break;
            }
        }
        // Convert SAT solution to a plan
        return buildPlan(solutionModel, planningProblem);
    }

    
    @Override
    public boolean isSupported(Problem planningProblem) {
        return !planningProblem.getRequirements().contains(RequireKey.ACTION_COSTS)
                && !planningProblem.getRequirements().contains(RequireKey.CONSTRAINTS)
                && !planningProblem.getRequirements().contains(RequireKey.CONTINOUS_EFFECTS)
                && !planningProblem.getRequirements().contains(RequireKey.DERIVED_PREDICATES)
                && !planningProblem.getRequirements().contains(RequireKey.DURATIVE_ACTIONS)
                && !planningProblem.getRequirements().contains(RequireKey.DURATION_INEQUALITIES)
                && !planningProblem.getRequirements().contains(RequireKey.FLUENTS)
                && !planningProblem.getRequirements().contains(RequireKey.GOAL_UTILITIES)
                && !planningProblem.getRequirements().contains(RequireKey.METHOD_CONSTRAINTS)
                && !planningProblem.getRequirements().contains(RequireKey.NUMERIC_FLUENTS)
                && !planningProblem.getRequirements().contains(RequireKey.OBJECT_FLUENTS)
                && !planningProblem.getRequirements().contains(RequireKey.PREFERENCES)
                && !planningProblem.getRequirements().contains(RequireKey.TIMED_INITIAL_LITERALS)
                && !planningProblem.getRequirements().contains(RequireKey.HIERARCHY);
    }
}