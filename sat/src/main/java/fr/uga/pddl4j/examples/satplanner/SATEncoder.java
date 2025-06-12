package fr.uga.pddl4j.examples.satplanner;

import fr.uga.pddl4j.problem.Problem;
import fr.uga.pddl4j.problem.Fluent;
import fr.uga.pddl4j.problem.operator.Action;
import fr.uga.pddl4j.util.BitVector;
import org.sat4j.core.Vec;
import org.sat4j.core.VecInt;
import org.sat4j.specs.IVecInt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Encodes a planning problem into SAT clauses for solving with a SAT solver
 */
public class SATEncoder {
    private Problem problem;       // The planning problem to encode
    private int horizonLength;    // Maximum number of time steps/actions in plan

    public SATEncoder(Problem problem, int horizonLength) {
        this.problem = problem;
        this.horizonLength = horizonLength;
    }

    /**
     * Gets unique SAT variable ID for a fluent at a specific time step
     */
    public int getFluentTemporalID(Fluent fluentState, int currentStep) {
        int fluentIndex = problem.getFluents().indexOf(fluentState);
        int totalComponents = problem.getFluents().size() + problem.getActions().size();
        return (totalComponents * currentStep) + 1 + fluentIndex;
    }

    /**
     * Gets unique SAT variable ID for an action at a specific time step
     */
    public int getActionTemporalID(Action selectedAction, int currentStep) {
        int actionIndex = problem.getActions().indexOf(selectedAction);
        int totalComponents = problem.getFluents().size() + problem.getActions().size();
        return (totalComponents * currentStep) + 1 + problem.getFluents().size() + actionIndex;
    }

    /**
     * Encodes the initial state as unit clauses (facts that must be true at step 0)
     */
    public Vec<IVecInt> initialStateEncoding() {
        Vec<IVecInt> startingClauses = new Vec<>();
        BitVector positiveInitFluents = problem.getInitialState().getPositiveFluents();
        Set<Integer> unassignedFluents = new HashSet<>();

        // Track all fluents not explicitly set in initial state
        for (int idx = 0; idx < problem.getFluents().size(); idx++) {
            unassignedFluents.add(idx);
        }

        // Add positive fluents from initial state
        for (int posIdx = positiveInitFluents.nextSetBit(0); posIdx >= 0; 
            posIdx = positiveInitFluents.nextSetBit(posIdx + 1)) {
            Fluent currentFluent = problem.getFluents().get(posIdx);
            unassignedFluents.remove(posIdx);
            
            VecInt posClause = new VecInt(new int[]{posIdx + 1});
            startingClauses.push(posClause);
        }

        // Add negative clauses for fluents not in initial state
        for (Integer missingIdx : unassignedFluents) {
            VecInt negClause = new VecInt(new int[]{-(missingIdx + 1)});
            startingClauses.push(negClause);
        }

        return startingClauses;
    }

    /**
     * Encodes goal conditions as unit clauses (facts that must be true at final step)
     */
    public Vec<IVecInt> goalStateEncoding() {
        Vec<IVecInt> targetClauses = new Vec<>();
        BitVector goalPositiveFluents = problem.getGoal().getPositiveFluents();

        // Create clause for each fluent in goal state
        for (int goalIdx = goalPositiveFluents.nextSetBit(0); goalIdx >= 0; 
            goalIdx = goalPositiveFluents.nextSetBit(goalIdx + 1)) {
            Fluent goalFluent = problem.getFluents().get(goalIdx);
            int temporalFluentID = getFluentTemporalID(goalFluent, horizonLength);
            VecInt goalClause = new VecInt(new int[]{temporalFluentID});
            targetClauses.push(goalClause);
        }

        return targetClauses;
    }

    /**
     * Encodes action preconditions and effects as implications
     */
    public Vec<IVecInt> actionConstraintsEncoding() {
        Vec<IVecInt> clauses = new Vec<>();

        // For each time step and each action
        for (int step = 0; step < horizonLength; step++) {
            for (Action act : problem.getActions()) {
                int actID = getActionTemporalID(act, step);

                // Encode preconditions: if action occurs, preconditions must hold
                BitVector posPre = act.getPrecondition().getPositiveFluents();
                for (int idx = posPre.nextSetBit(0); idx >= 0; idx = posPre.nextSetBit(idx + 1)) {
                    int fluentID = getFluentTemporalID(problem.getFluents().get(idx), step);
                    clauses.push(new VecInt(new int[]{-actID, fluentID}));
                }

                BitVector negPre = act.getPrecondition().getNegativeFluents();
                for (int idx = negPre.nextSetBit(0); idx >= 0; idx = negPre.nextSetBit(idx + 1)) {
                    int fluentID = getFluentTemporalID(problem.getFluents().get(idx), step);
                    clauses.push(new VecInt(new int[]{-actID, -fluentID}));
                }

                // Encode effects: if action occurs, effects must occur
                BitVector posEff = act.getUnconditionalEffect().getPositiveFluents();
                for (int idx = posEff.nextSetBit(0); idx >= 0; idx = posEff.nextSetBit(idx + 1)) {
                    int fluentID = getFluentTemporalID(problem.getFluents().get(idx), step + 1);
                    clauses.push(new VecInt(new int[]{-actID, fluentID}));
                }

                BitVector negEff = act.getUnconditionalEffect().getNegativeFluents();
                for (int idx = negEff.nextSetBit(0); idx >= 0; idx = negEff.nextSetBit(idx + 1)) {
                    int fluentID = getFluentTemporalID(problem.getFluents().get(idx), step + 1);
                    clauses.push(new VecInt(new int[]{-actID, -fluentID}));
                }
            }
        }
        return clauses;
    }

    /**
     * Encodes frame axioms - rules about when fluents can change between steps
     */
    public Vec<IVecInt> frameAxiomsEncoding() {
        Vec<IVecInt> clauses = new Vec<>();
        List<Action>[] posActions = new List[problem.getFluents().size()];
        List<Action>[] negActions = new List[problem.getFluents().size()];

        // Initialize lists to track which actions affect each fluent
        for (int i = 0; i < problem.getFluents().size(); i++) {
            posActions[i] = new ArrayList<>();
            negActions[i] = new ArrayList<>();
        }

        // Map each action to the fluents it affects
        for (Action act : problem.getActions()) {
            BitVector posEff = act.getUnconditionalEffect().getPositiveFluents();
            for (int idx = posEff.nextSetBit(0); idx >= 0; idx = posEff.nextSetBit(idx + 1)) {
                posActions[idx].add(act);
            }

            BitVector negEff = act.getUnconditionalEffect().getNegativeFluents();
            for (int idx = negEff.nextSetBit(0); idx >= 0; idx = negEff.nextSetBit(idx + 1)) {
                negActions[idx].add(act);
            }
        }

        // Create frame axioms for each fluent
        for (int fIdx = 0; fIdx < problem.getFluents().size(); fIdx++) {
            Fluent f = problem.getFluents().get(fIdx);
            for (int step = 0; step < horizonLength; step++) {
                // If fluent changes from true to false, some action must have caused it
                if (!posActions[fIdx].isEmpty()) {
                    VecInt clause = new VecInt();
                    clause.push(getFluentTemporalID(f, step));
                    clause.push(-getFluentTemporalID(f, step + 1));
                    for (Action act : posActions[fIdx]) {
                        clause.push(getActionTemporalID(act, step));
                    }
                    clauses.push(clause);
                }

                // If fluent changes from false to true, some action must have caused it
                if (!negActions[fIdx].isEmpty()) {
                    VecInt clause = new VecInt();
                    clause.push(-getFluentTemporalID(f, step));
                    clause.push(getFluentTemporalID(f, step + 1));
                    for (Action act : negActions[fIdx]) {
                        clause.push(getActionTemporalID(act, step));
                    }
                    clauses.push(clause);
                }
            }
        }
        return clauses;
    }

    /**
     * Encodes that only one action can occur at each time step
     */
    public Vec<IVecInt> mutualExclusionEncoding() {
        Vec<IVecInt> exclusions = new Vec<>();
        int componentCount = problem.getActions().size() + problem.getFluents().size();
        
        // For each pair of actions, add clause preventing them from both occurring
        for (int i = 0; i < problem.getActions().size(); i++) {
            for (int j = 0; j < i; j++) {
                Action a1 = problem.getActions().get(i);
                Action a2 = problem.getActions().get(j);
                
                for (int t = 0; t < horizonLength; t++) {
                    int offset = componentCount * t;
                    exclusions.push(new VecInt(new int[]{
                        -(getActionTemporalID(a1, 0) + offset),
                        -(getActionTemporalID(a2, 0) + offset)
                    }));
                }
            }
        }
        return exclusions;
    }

    /**
     * Combines all encodings into complete CNF formula for SAT solver
     */
    public Vec<IVecInt> convertToCNF() {
        Vec<IVecInt> cnf = new Vec<>();
        Stream.of(
            initialStateEncoding(),
            goalStateEncoding(),
            actionConstraintsEncoding(),
            frameAxiomsEncoding(),
            mutualExclusionEncoding()
        ).forEach(clauses -> clauses.copyTo(cnf));
        return cnf;
    }
}