import React from 'react';
import { shallow } from 'enzyme';
import Experiment from 'domain/experiments/Experiment';
import { plainToClass } from 'class-transformer';
import { experimentMock } from 'testData';
import { storeFactory } from 'functions/testUtils';

import ExperimentSummary from '../../../components/experiments-overview/ExperimentSummary';

const store = storeFactory({
});

const setup = () => shallow(
  <ExperimentSummary
    store={store}
    experiment={plainToClass(Experiment, experimentMock)}
    projectId={123456}
    defaultBranch="master"
  />,
).dive().dive();

describe('functionality tests', () => {
  let wrapper;
  beforeEach(() => {
    wrapper = setup();
  });
  test('assert that after clicking dropdown button the chart is rendered', () => {
    const mockEvent = { currentTarget: { classList: { contains: () => {} } } };
    const btn = wrapper.find('ArrowButton')
      .first()
      .dive()
      .find(`button#ArrowButton-${experimentMock.slug}`);

    btn.simulate('click', mockEvent);
    expect(wrapper.find('.data-summary')).toHaveLength(1);
  });
});
